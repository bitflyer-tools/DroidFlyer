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
import io.reactivex.disposables.Disposable
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

    fun updateStatus() {
        apiClient.getPositions()
            .subscribeOnIoObserveOnUI()
            .subscribeRefresh(bag) { position.value = it }

        apiClient.getCollateral()
            .subscribeOnIoObserveOnUI()
            .map { it["collateral"] as? Double ?: 0.0 }
            .subscribeRefresh(bag) { balance.value = it }

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .subscribeRefresh(bag) { board.value = it }

        apiClient.getChildOrders("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .subscribeRefresh(bag) { orders.value = it }
    }

    private fun orderEvents(): Observable<Unit> = Observable.merge(
        realtimeClient.childOrderEvents.map { Unit },
        realtimeClient.parentOrderEvents.map { Unit }
    )
}

internal fun <T : Any> Observable<T>.subscribeRefresh(bag: CompositeDisposable, onNext: (T) -> Unit) {
    lateinit var subscription: Disposable
    doFinally { bag.delete(subscription) }
        .doOnSubscribe {
            subscription = it
            bag.add(it)
        }
        .subscribeNext(onNext)
}

internal fun boardUpdates(
    snapshots: Observable<Board>,
    deltas: Observable<Board>
): Observable<(Board) -> Board> = Observable.merge(
    snapshots.map<(Board) -> Board> { snapshot -> { snapshot } },
    deltas.map<(Board) -> Board> { delta -> { book -> book.merge(delta) } }
)
