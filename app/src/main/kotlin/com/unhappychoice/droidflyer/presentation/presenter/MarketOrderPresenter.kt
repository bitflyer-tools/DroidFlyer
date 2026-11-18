package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.withNextSnackBar
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class MarketOrderPresenter(
    val apiClient: APIClientV1,
    val currentStatusService: CurrentStatusService
) : ViewPresenter<MarketOrderView>(), HasAmount, Loadable {
    override val isLoading = Variable(false)
    override val unitSize = Variable(1.0)
    override val amount = Variable(0.0)

    private val closePositionService = ClosePositionService(apiClient)
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun buy() {
        if (isLoading.value || amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .withNextSnackBar(view, view.context.getString(R.string.bought_coins, Math.abs(amount.value)))
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }

    fun sell() {
        if (isLoading.value || amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .withNextSnackBar(view, view.context.getString(R.string.sold_coins, Math.abs(amount.value)))
            .subscribeNext { currentStatusService.updateStatus() }
            .addTo(bag)
    }

    fun clearPosition(confirmedSize: Double) {
        if (isLoading.value || confirmedSize == 0.0) return
        closePositionService.close(confirmedSize)
            .map { R.string.cleared_all_positions }
            .onErrorReturn { R.string.close_positions_incomplete }
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext { message ->
                currentStatusService.updateStatus()
                view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
            }
            .addTo(bag)
    }
}
