package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<(Board) -> Board>,
    orderEvents: Observable<Unit>,
    privateClient: APIClientV1? = apiClient
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1?) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        realtimeClient.orderChanges,
        privateClient
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

    init {
        executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        boardUpdates
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { update -> board.value = update(board.value) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { it.updateMarketQuotes(buyPrice, sellPrice) }
            .addTo(bag)

        orderEvents
            .subscribeNext { updateStatus() }
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

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(board)
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
