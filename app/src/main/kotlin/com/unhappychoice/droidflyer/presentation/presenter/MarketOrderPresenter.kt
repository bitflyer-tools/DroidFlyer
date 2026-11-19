package com.unhappychoice.droidflyer.presentation.presenter

import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.CloseConfirmation
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import io.reactivex.Observable
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

    internal fun positionCloseOrder(): CloseConfirmation? =
        currentStatusService.privateState.closeConfirmation()

    internal fun clearPosition(
        confirmed: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit
    ) {
        val state = currentStatusService.privateState
        val order = state.closePosition(confirmed, reconfirm) ?: return
        submit(order) {
            if (!state.isCurrent(confirmed)) return@submit
            reportAcceptance()
            awaitPositionClose()
            currentStatusService.updateStatus()
        }
    }

    private fun submit(side: String, size: Double) {
        if (size == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(size))
        submit(apiClient.sendChildOrder(request)) {
            reportAcceptance()
            currentStatusService.updateStatus()
        }
    }

    private fun submit(order: Observable<Map<String, Any>>, onAccepted: () -> Unit) {
        order
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribe({ onAccepted() }, { notify(R.string.market_order_failed) })
            .addTo(bag)
    }

    private fun reportAcceptance() = notify(R.string.market_order_accepted)

    private fun awaitPositionClose() {
        closeConfirmation.set(currentStatusService.privateState.observeNewPositionResponses()
            .filter { positions -> positions.isEmpty() }
            .take(1)
            .subscribeNext { notify(R.string.cleared_all_positions) })
    }

    private fun showMessage(message: Int) {
        val currentView = view ?: return
        Snackbar.make(currentView, message, Snackbar.LENGTH_SHORT).show()
    }
}
