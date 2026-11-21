package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<BoardUpdate>,
    orderEvents: Observable<Unit>,
    privateClient: APIClientV1? = apiClient,
    parentEvents: Observable<List<ParentOrderEvent>> = Observable.empty(),
    privateChannelsReady: Observable<Unit> = Observable.empty(),
    childEvents: Observable<List<ChildOrderEvent>> = Observable.empty(),
    privateChannelsDisconnected: Observable<Unit> = Observable.empty(),
    privateChannelAuthenticationFailed: Observable<Unit> = Observable.empty()
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        realtimeClient.childOrderEvents.map { Unit },
        privateClient,
        realtimeClient.parentOrderEvents,
        realtimeClient.privateChannelsReady,
        realtimeClient.childOrderEvents,
        realtimeClient.privateChannelsDisconnected,
        realtimeClient.privateChannelAuthenticationFailed
    )

    internal val privateState = PrivateTradingState(privateClient)

    internal val currentPrice = Variable(MarketPrice(null))
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val balance = privateState.balance
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), balance.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val restBoards = PublishSubject.create<BoardUpdate>().toSerialized()
    private val bag = CompositeDisposable()
    private var accountChanged = CompletableSubject.create()
    private val statusRefresh = StatusRefreshCoordinator(::refreshStatus)
    private val realtimeBoardRevision = AtomicLong()

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

        val parentUpdates = parentEvents.filter { batch -> batch.any { it.productCode == "FX_BTC_JPY" } }
        Observable.merge(orderEvents, parentUpdates.map { Unit })
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        val stopOrders = privateState.stopOrders
        val childUpdates = childEvents.filter { batch ->
            batch.any { it.productCode == "FX_BTC_JPY" && it.affectsStopOrderStatus() }
        }
        parentUpdates
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext(stopOrders::onEvents)
            .sample(1, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { stopOrders.onParentUpdates() }
            .addTo(bag)

        childUpdates.sample(1, TimeUnit.SECONDS, true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { stopOrders.onChildUpdates() }
            .addTo(bag)

        Observable.merge(
            privateChannelsReady.map { true },
            privateChannelsDisconnected.map { false },
            privateChannelAuthenticationFailed.map { false }
        ).observeOn(AndroidSchedulers.mainThread())
            .subscribeNext(stopOrders::onPrivateChannelAvailability)
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

    internal fun switchAccount(client: APIClientV1?) {
        if (bag.isDisposed) return

        val previous = accountChanged
        accountChanged = CompletableSubject.create()
        privateState.switchAccount(client)
        // Queue the new account before ending an in-flight refresh.
        updateStatus()
        previous.onComplete()
    }

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
