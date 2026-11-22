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
    orderEvents: Observable<PrivateEvent<List<ChildOrderEvent>>>,
    privateClient: APIClientV1? = apiClient,
    parentEvents: Observable<PrivateEvent<List<ParentOrderEvent>>> = Observable.empty(),
    privateChannelAvailability: Observable<PrivateEvent<Boolean>> = Observable.empty(),
    submissionGuard: StopSubmissionGuard = StopSubmissionGuard.inMemory()
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?) :
        this(apiClient, realtimeClient, privateClient, StopSubmissionGuard.inMemory())

    internal constructor(
        apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?,
        submissionGuard: StopSubmissionGuard
    ) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        realtimeClient.childOrderEvents,
        privateClient,
        realtimeClient.parentOrderEvents,
        realtimeClient.privateChannelAvailability,
        submissionGuard
    )

    internal val privateState = PrivateTradingState(privateClient, submissionGuard = submissionGuard)

    internal val currentPrice = Variable(MarketPrice(null))
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), privateState.collateral.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val restBoards = PublishSubject.create<BoardUpdate>().toSerialized()
    private val bag = CompositeDisposable()
    private var accountChanged = CompletableSubject.create()
    private var channelAvailability: PrivateEvent<Boolean>? = null
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

        val parentUpdates = parentEvents.filter { batch -> batch.value.any { it.productCode == "FX_BTC_JPY" } }
        Observable.merge<PrivateEvent<*>>(orderEvents, parentUpdates)
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .filter { it.isCurrent() }
            .subscribeNext { updateStatus() }
            .addTo(bag)

        val stopOrders = privateState.stopOrders
        val childUpdates = orderEvents.filter { batch ->
            batch.value.any { it.productCode == "FX_BTC_JPY" && it.affectsStopOrderStatus() }
        }
        parentUpdates
            .observeOn(AndroidSchedulers.mainThread())
            .filter { it.isCurrent() }
            .doOnNext { stopOrders.onEvents(it.value) }
            .sample(1, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .filter { it.isCurrent() }
            .subscribeNext { stopOrders.onParentUpdates() }
            .addTo(bag)

        childUpdates.observeOn(AndroidSchedulers.mainThread())
            .filter { it.isCurrent() }
            .doOnNext { stopOrders.onChildEvents(it.value) }
            .sample(1, TimeUnit.SECONDS, true)
            .observeOn(AndroidSchedulers.mainThread())
            .filter { it.isCurrent() }
            .subscribeNext { stopOrders.onChildUpdates() }
            .addTo(bag)

        privateChannelAvailability.observeOn(AndroidSchedulers.mainThread())
            .filter { it.isCurrent() }
            .subscribeNext {
                channelAvailability = it
                stopOrders.onPrivateChannelAvailability(it.value)
            }
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

    internal fun initializeAccount(loadAccount: () -> Pair<APIClientV1, StopSubmissionGuard>?) {
        val initialAccount = accountChanged
        Maybe.fromCallable<Pair<APIClientV1, StopSubmissionGuard>> { loadAccount() }
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { (client, guard) ->
                if (accountChanged === initialAccount) switchAccount(client, guard)
            }
            .addTo(bag)
    }

    internal fun switchAccount(client: APIClientV1?, guard: StopSubmissionGuard = StopSubmissionGuard.inMemory()) {
        if (bag.isDisposed) return

        val previous = accountChanged
        accountChanged = CompletableSubject.create()
        privateState.switchAccount(client, guard) {
            channelAvailability?.takeIf { client != null && it.value && it.isCurrent() }?.let {
                privateState.stopOrders.onPrivateChannelAvailability(true)
            }
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
