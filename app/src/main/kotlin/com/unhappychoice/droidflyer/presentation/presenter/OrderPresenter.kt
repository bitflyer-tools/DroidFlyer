package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.presentation.view.OrderView
import com.unhappychoice.norimaki.extension.subscribeNext
import com.unhappychoice.norimaki.extension.subscribeOnIoObserveOnUI
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class OrderPresenter(
    val apiClient: APIClientV1,
    val realtimeClient: RealtimeClient
) : ViewPresenter<OrderView>() {
    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0L)
    val board = Variable(Board(0.0, listOf(), listOf()))

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

        apiClient.getPositions()
            .doOnError { it }
            .subscribeOnIoObserveOnUI()
            .subscribeNext { position.value = it }
            .addTo(bag)

        apiClient.getCollateral()
            .subscribeOnIoObserveOnUI()
            .doOnError { it }
            .subscribeNext { balance.value = it["collateral"] as? Long ?: 0 }
            .addTo(bag)

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

    fun buy() {

    }

    fun sell() {

    }

    fun clearPosition() {

    }
}