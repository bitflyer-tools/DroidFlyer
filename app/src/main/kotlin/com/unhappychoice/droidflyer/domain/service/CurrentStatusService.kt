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
import java.util.concurrent.TimeUnit

class CurrentStatusService(val apiClient: APIClientV1, val realtimeClient: RealtimeClient) {
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0.0)
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), balance.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = Variable<List<ChildOrder>>(listOf())

    private val bag = CompositeDisposable()

    init {
        realtimeClient.executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { update -> board.value = update(board.value) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { book ->
                buyPrice.value = book.asks.map { it.price }.minOrNull() ?: 0
                sellPrice.value = book.bids.map { it.price }.maxOrNull() ?: 0
            }.addTo(bag)

        orderEvents()
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun dispose() = bag.dispose()

    fun updateStatus() {
        if (bag.isDisposed) return

        apiClient.getPositions()
            .subscribeOnIoObserveOnUI()
            .bindTo(position)
            .addTo(bag)

        apiClient.getCollateral()
            .subscribeOnIoObserveOnUI()
            .map { it["collateral"] as? Double ?: 0.0 }
            .bindTo(balance)
            .addTo(bag)

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(board)
            .addTo(bag)

        apiClient.getChildOrders("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(orders)
            .addTo(bag)
    }

    private fun orderEvents(): Observable<Unit> = Observable.merge(
        realtimeClient.childOrderEvents.map { Unit },
        realtimeClient.parentOrderEvents.map { Unit }
    )
}

internal fun boardUpdates(
    snapshots: Observable<Board>,
    deltas: Observable<Board>
): Observable<(Board) -> Board> = Observable.merge(
    snapshots.map<(Board) -> Board> { snapshot -> { snapshot } },
    deltas.map<(Board) -> Board> { delta -> { book -> book.merge(delta) } }
)
