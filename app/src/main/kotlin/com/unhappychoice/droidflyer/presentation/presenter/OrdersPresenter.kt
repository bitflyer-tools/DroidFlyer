package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.extension.withLog
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.view.OrdersView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter
import java.util.concurrent.TimeUnit

class OrdersPresenter(
    val api: APIClientV1,
    val currentStatusService: CurrentStatusService
) : ViewPresenter<OrdersView>() {
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)

        Observable.interval(6, TimeUnit.SECONDS)
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun cancelOrder(order: ChildOrder) {
        val request = CancelChildOrderRequest("FX_BTC_JPY", order.childOrderAcceptanceId)
        api.cancelChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }
}
