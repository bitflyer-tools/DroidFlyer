package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.view.OrderView
import com.unhappychoice.norimaki.extension.subscribeNext
import com.unhappychoice.norimaki.extension.subscribeOnIoObserveOnUI
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter
import java.util.concurrent.TimeUnit

class OrderPresenter(
    val apiClient: APIClientV1,
    val realtimeClient: RealtimeClient
) : ViewPresenter<OrderView>() {
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0.0)
    val board = Variable(Board(0.0, listOf(), listOf()))

    val size = Variable(1.0)
    val amount = Variable(0.0)

    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)

        realtimeClient.executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        realtimeClient.boardSnapshot
            .subscribeOnIoObserveOnUI()
            .subscribeNext { board.value = it }
            .addTo(bag)

        Observable.interval(5, TimeUnit.SECONDS)
            .subscribeNext {
                apiClient.getPositions()
                    .subscribeOnIoObserveOnUI()
                    .subscribeNext { position.value = it }
                    .addTo(bag)

                apiClient.getCollateral()
                    .subscribeOnIoObserveOnUI()
                    .subscribeNext { balance.value = it["collateral"] as? Double ?: 0.0 }
                    .addTo(bag)
            }

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext {
                buyPrice.value = board.value.asks.map { it.price }.min() ?: 0
                sellPrice.value = board.value.bids.map { it.price }.max() ?: 0
            }.addTo(bag)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun profit(): Long = position.value.profit(currentPrice.value.toLong()).toLong()

    fun increment() {
        amount.value = amount.value + size.value
    }

    fun decrement() {
        amount.value = amount.value - size.value
        if (amount.value <= 0) amount.value = 0.0
    }

    fun buy() {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .subscribeNext {}
            .addTo(bag)
    }

    fun sell() {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .subscribeNext {}
            .addTo(bag)
    }

    fun clearPosition() {
        val size = position.value.wholeSize()
        if (size == 0.0) return
        val side = if (size > 0.0) "SELL" else "BUY"
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(size))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .subscribeNext {}
            .addTo(bag)
    }
}