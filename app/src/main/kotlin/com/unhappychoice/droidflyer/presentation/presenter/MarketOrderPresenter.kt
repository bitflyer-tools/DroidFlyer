package com.unhappychoice.droidflyer.presentation.presenter

import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.SerialDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class MarketOrderPresenter internal constructor(
    private val apiClient: APIClientV1,
    private val currentStatusService: CurrentStatusService,
    private val notify: MarketOrderPresenter.(Int) -> Unit
) : ViewPresenter<MarketOrderView>(), HasAmount, Loadable {
    constructor(apiClient: APIClientV1, currentStatusService: CurrentStatusService) : this(
        apiClient,
        currentStatusService,
        MarketOrderPresenter::showMessage
    )

    override val isLoading = Variable(false)
    override val unitSize = Variable(1.0)
    override val amount = Variable(0.0)

    private val closeConfirmation = SerialDisposable()
    private val bag = CompositeDisposable(closeConfirmation)

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun buy() = submit("BUY", amount.value)

    fun sell() = submit("SELL", amount.value)

    fun clearPosition() {
        val size = currentStatusService.position.value.wholeSize()
        val side = if (size > 0.0) "SELL" else "BUY"
        submit(side, size, ::awaitPositionClose)
    }

    private fun submit(side: String, size: Double, onAccepted: () -> Unit = {}) {
        if (size == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(size))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext {
                notify(R.string.market_order_accepted)
                onAccepted()
                currentStatusService.updateStatus()
            }
            .addTo(bag)
    }

    private fun awaitPositionClose() {
        closeConfirmation.set(currentStatusService.observeNewPositionResponses()
            .filter { positions -> positions.isEmpty() }
            .take(1)
            .subscribeNext { notify(R.string.cleared_all_positions) })
    }

    private fun showMessage(message: Int) {
        val currentView = view ?: return
        Snackbar.make(currentView, message, Snackbar.LENGTH_SHORT).show()
    }
}
