package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.PrivateEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<BoardUpdate>,
    orderEvents: Observable<PrivateEvent<*>>,
    privateClient: APIClientV1? = apiClient
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        Observable.merge(
            realtimeClient.childOrderEvents,
            realtimeClient.parentOrderEvents
        ),
        privateClient
    )

    internal val privateState = PrivateTradingState(privateClient)

    internal val currentPrice = Variable(MarketPrice(null))
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    internal val tradingHeader = observeTradingHeader(
        currentPrice.asObservable(), position.asObservable(), privateState.collateral.asObservable()
    )
    val tradingTotals = tradingHeader.map { it.totals }
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val restBoards = PublishSubject.create<BoardUpdate>().toSerialized()
    private val bag = CompositeDisposable()
    private var accountChanged = CompletableSubject.create()
    private val statusRefresh = StatusRefreshCoordinator(::refreshStatus)
    private val realtimeBoardRevision = AtomicLong()
    private val fundingStatus = AtomicReference(FundingStatus())

    init {
        executions
            .filter { it.isNotEmpty() }
            .subscribeOnIoObserveOnUI()
            .subscribeNext { batch ->
                batch.firstOrNull { it.price.isFinite() && it.price > 0.0 }?.let {
                    currentPrice.value = MarketPrice(it.price)
                }
            }
            .addTo(bag)

        val realtimeBoards = boardUpdates.doOnNext { realtimeBoardRevision.incrementAndGet() }
        observeOrderBook(Observable.merge(realtimeBoards, restBoards), Schedulers.single()) { update ->
            update.requestRevision?.let { it == realtimeBoardRevision.get() } ?: true
        }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { event ->
                when (event) {
                    is OrderBookEvent.View -> board.value = event.board
                    is OrderBookEvent.Quotes -> {
                        if (buyPrice.value != event.bestAsk) buyPrice.value = event.bestAsk
                        if (sellPrice.value != event.bestBid) sellPrice.value = event.bestBid
                    }
                }
            }
            .addTo(bag)

        orderEvents
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .filter { it.isCurrent() }
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun dispose() {
        bag.dispose()
        statusRefresh.dispose()
        privateState.dispose()
    }

    fun updateStatus() = statusRefresh.request(followUp = true)

    fun pollStatus() = statusRefresh.request(followUp = false)

    internal fun initializeAccount(loadClient: () -> APIClientV1?) {
        val initialAccount = accountChanged
        Maybe.fromCallable<APIClientV1> { loadClient() }
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { client ->
                if (accountChanged === initialAccount) switchAccount(client)
            }
            .addTo(bag)
    }

    internal fun switchAccount(client: APIClientV1?) {
        if (bag.isDisposed) return

        val previous = accountChanged
        accountChanged = CompletableSubject.create()
        privateState.switchAccount(client) {
            // Queue the new account before ending an in-flight refresh.
            updateStatus()
            previous.onComplete()
        }
    }

    internal fun observeFunding(visibility: Observable<Boolean>): Observable<FundingStatus> =
        Observable.defer {
            visibility.distinctUntilChanged()
                .switchMap { visible -> if (visible) pollFunding() else Observable.empty() }
                .map(::updateFundingStatus)
                .startWith(fundingStatus.get())
                .distinctUntilChanged()
        }

    private fun updateFundingStatus(update: FundingStatus): FundingStatus {
        do {
            val previous = fundingStatus.get()
            val next = if (update.funding != null) update else previous.copy(stale = previous.funding != null)
            if (fundingStatus.compareAndSet(previous, next)) return next
        } while (true)
    }

    private fun pollFunding(): Observable<FundingStatus> =
        Observable.defer { apiClient.getFundingRate("FX_BTC_JPY") }
            .firstOrError()
            .map { FundingStatus(it.toNextFunding()) }
            .onErrorReturnItem(FundingStatus())
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .repeatWhen { it.delay(1, TimeUnit.MINUTES) }

    private fun refreshStatus(): Completable = Completable.ambArray(
        accountChanged,
        Completable.mergeArray(privateState.refresh(), refreshBoard())
    )

    private fun refreshBoard(): Completable {
        val requestRevision = realtimeBoardRevision.get()
        return apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .publishStatus { snapshot ->
                restBoards.onNext(BoardUpdate(snapshot, BoardSource.REST, requestRevision))
            }
    }
}
