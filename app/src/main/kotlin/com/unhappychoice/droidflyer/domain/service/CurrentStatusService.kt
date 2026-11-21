package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<(Board) -> Board>,
    orderEvents: Observable<Unit>,
    privateClient: APIClientV1 = apiClient,
    parentEvents: Observable<List<ParentOrderEvent>> = Observable.empty(),
    privateChannelsReady: Observable<Unit> = Observable.empty(),
    childEvents: Observable<List<ChildOrderEvent>> = Observable.empty(),
    privateChannelsDisconnected: Observable<Unit> = Observable.empty(),
    privateChannelAuthenticationFailed: Observable<Unit> = Observable.empty()
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1) : this(
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

    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val balance = privateState.balance
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), balance.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val bag = CompositeDisposable()
    private val realtimeBoardRevision = AtomicLong()

    init {
        executions
            .filter { it.isNotEmpty() }
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        boardUpdates
            .doOnNext { realtimeBoardRevision.incrementAndGet() }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { update -> board.value = update(board.value) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { it.updateMarketQuotes(buyPrice, sellPrice) }
            .addTo(bag)

        orderEvents
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        val stopOrders = privateState.stopOrders
        val parentUpdates = parentEvents
            .observeOn(AndroidSchedulers.mainThread())
            .filter { batch -> batch.any { it.productCode == "FX_BTC_JPY" } }
            .doOnNext(stopOrders::onEvents)
        val childUpdates = childEvents.filter { batch ->
            batch.any { it.productCode == "FX_BTC_JPY" && it.affectsStopOrderStatus() }
        }
        parentUpdates.sample(1, TimeUnit.SECONDS)
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
        privateState.dispose()
    }

    fun updateStatus() {
        if (bag.isDisposed) return

        privateState.refresh()

        refreshBoard()
    }

    private fun refreshBoard() {
        val requestRevision = realtimeBoardRevision.get()
        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .subscribeNext { snapshot ->
                if (realtimeBoardRevision.get() == requestRevision) board.value = snapshot
            }
            .addTo(bag)
    }
}

internal fun Board.updateMarketQuotes(buyPrice: Variable<Long>, sellPrice: Variable<Long>) {
    val bestAsk = asks.minOfOrNull(Order::price) ?: 0L
    val bestBid = bids.maxOfOrNull(Order::price) ?: 0L
    if (buyPrice.value != bestAsk) buyPrice.value = bestAsk
    if (sellPrice.value != bestBid) sellPrice.value = bestBid
}

internal fun boardUpdates(
    snapshots: Observable<Board>,
    deltas: Observable<Board>
): Observable<(Board) -> Board> = Observable.merge(
    snapshots.map<(Board) -> Board> { snapshot -> { snapshot } },
    deltas.map<(Board) -> Board> { delta -> { book -> book.merge(delta) } }
)
