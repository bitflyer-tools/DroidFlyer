package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit

class CurrentStatusService(
    private val apiClient: APIClientV1,
    private val realtimeClient: RealtimeClient,
    privateClient: APIClientV1
) {
    internal val privateState = PrivateTradingState(privateClient)
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val balance = privateState.balance
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val bag = CompositeDisposable()

    init {
        realtimeClient.executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        realtimeClient.board
            .subscribeOnIoObserveOnUI()
            .subscribeNext { board.value = board.value.merge(it) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext {
                buyPrice.value = board.value.asks.map { it.price }.minOrNull() ?: 0
                sellPrice.value = board.value.bids.map { it.price }.maxOrNull() ?: 0
            }.addTo(bag)

        orderEvents()
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun profit(): Long = position.value.profit(currentPrice.value.toLong()).toLong()

    fun spread(): Long = currentPrice.value.toLong() - position.value.average().toLong()

    fun updateStatus() {
        privateState.refresh()

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(board)
            .addTo(bag)
    }

    private fun orderEvents(): Observable<Unit> = Observable.merge(
        realtimeClient.childOrderEvents.map { Unit },
        realtimeClient.parentOrderEvents.map { Unit }
    )
}
