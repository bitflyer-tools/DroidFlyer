package com.unhappychoice.droidflyer.presentation.presenter

import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
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
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class MarketOrderPresenter internal constructor(
    private val apiClient: APIClientV1,
    private val currentStatusService: CurrentStatusService,
    private val closePositionService: ClosePositionService = ClosePositionService(apiClient),
    private val notify: MarketOrderPresenter.(Int) -> Unit
) : ViewPresenter<MarketOrderView>(), HasAmount, Loadable {
    constructor(apiClient: APIClientV1, currentStatusService: CurrentStatusService) : this(
        apiClient,
        currentStatusService,
        notify = MarketOrderPresenter::showMessage
    )

    override val isLoading = Variable(false)
    override val unitSize = Variable(1.0)
    override val amount = Variable(0.0)

    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun buy() = submit("BUY", amount.value)

    fun sell() = submit("SELL", amount.value)

    internal fun positionCloseOrder(): PositionCloseOrder? =
        PositionCloseOrder.from(currentStatusService.position.value.wholeSize())

    internal fun clearPosition(
        confirmed: PositionCloseOrder,
        reconfirm: (PositionCloseOrder) -> Unit
    ) {
        if (isLoading.value) return
        confirmed.confirm(positionCloseOrder(), ::submitPositionClose, reconfirm)
    }

    private fun submitPositionClose(order: PositionCloseOrder) {
        closePositionService.close(order.positionSize) {
            AndroidSchedulers.mainThread()
                .scheduleDirect { notify(R.string.market_order_accepted) }
                .addTo(bag)
        }
            .map { R.string.cleared_all_positions }
            .onErrorReturnItem(R.string.close_positions_incomplete)
            .toObservable()
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext { message ->
                currentStatusService.updateStatus()
                notify(message)
            }
            .addTo(bag)
    }

    private fun submit(side: String, size: Double) {
        if (isLoading.value || size == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(size))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNext {
                notify(R.string.market_order_accepted)
                currentStatusService.updateStatus()
            }
            .addTo(bag)
    }

    private fun showMessage(message: Int) {
        val currentView = view ?: return
        val duration = if (message == R.string.market_order_accepted) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
        Snackbar.make(currentView, message, duration).show()
    }
}
