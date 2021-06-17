package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

class CurrentStatusService(val apiClient: APIClientV1, val realtimeClient: RealtimeClient) {
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0.0)
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = Variable<List<ChildOrder>>(listOf())

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

        updateStatus()
    }

    fun profit(): Long = position.value.profit(currentPrice.value.toLong()).toLong()

    fun spread(): Long = currentPrice.value.toLong() - position.value.average().toLong()

    fun updateStatus() {
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
}
