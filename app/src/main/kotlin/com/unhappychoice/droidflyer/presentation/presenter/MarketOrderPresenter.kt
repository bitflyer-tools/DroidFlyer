package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.round
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import com.unhappychoice.norimaki.extension.subscribeNext
import com.unhappychoice.norimaki.extension.subscribeOnIoObserveOnUI
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class MarketOrderPresenter(
    val apiClient: APIClientV1,
    val currentStatusService: CurrentStatusService
) : ViewPresenter<MarketOrderView>(), Loadable {
    override val isLoading = Variable(false)

    val size = Variable(1.0)
    val amount = Variable(0.0)

    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun increment() {
        amount.value = (amount.value + size.value).round(8)
    }

    fun decrement() {
        amount.value = (amount.value - size.value).round(8)
        if (amount.value <= 0) amount.value = 0.0
    }

    fun buy() {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }

    fun sell() {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }

    fun clearPosition() {
        val size = currentStatusService.position.value.wholeSize()
        if (size == 0.0) return
        val side = if (size > 0.0) "SELL" else "BUY"
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(size))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }
}