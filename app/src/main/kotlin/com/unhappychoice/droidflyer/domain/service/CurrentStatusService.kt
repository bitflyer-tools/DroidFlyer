package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import com.unhappychoice.norimaki.extension.subscribeNext
import com.unhappychoice.norimaki.extension.subscribeOnIoObserveOnUI
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

class CurrentStatusService(val apiClient: APIClientV1, val realtimeClient: RealtimeClient) {
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0.0)
    val board = Variable(Board(0.0, listOf(), listOf()))

    private val bag = CompositeDisposable()

    init {
        realtimeClient.executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        realtimeClient.boardSnapshot
            .subscribeOnIoObserveOnUI()
            .subscribeNext { board.value = it }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext {
                buyPrice.value = board.value.asks.map { it.price }.min() ?: 0
                sellPrice.value = board.value.bids.map { it.price }.max() ?: 0
            }.addTo(bag)
    }

    fun profit(): Long = position.value.profit(currentPrice.value.toLong()).toLong()

    fun updateStatus() {
        apiClient.getPositions()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { position.value = it }
            .addTo(bag)

        apiClient.getCollateral()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { balance.value = it["collateral"] as? Double ?: 0.0 }
            .addTo(bag)
    }
}
