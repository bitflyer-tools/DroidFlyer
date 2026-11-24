package com.unhappychoice.droidflyer.presentation.presenter

import android.content.res.Resources
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.CloseConfirmation
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.OrderAcceptanceUncertainException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class MarketOrderPresenter internal constructor(
    private val currentStatusService: CurrentStatusService,
    internal val maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM),
    private val notify: (Int) -> Unit
) : ViewPresenter<MarketOrderView>(), HasAmount, Loadable {
    constructor(
        currentStatusService: CurrentStatusService,
        accountPreference: CryptoCfdAccountPreference,
        resources: Resources,
        showSnackbar: (String, Int) -> Unit
    ) : this(
        currentStatusService,
        accountPreference.maximumOrderSize,
        notify = { message ->
            val duration = if (message == R.string.market_order_accepted) Snackbar.LENGTH_SHORT else Snackbar.LENGTH_LONG
            showSnackbar(resources.getString(message), duration)
        }
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

    internal fun positionCloseOrder(): CloseConfirmation? =
        currentStatusService.privateState.closeConfirmation()

    internal fun clearPosition(
        confirmed: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit
    ) {
        if (isLoading.value) return
        val state = currentStatusService.privateState
        val close = state.closePosition(confirmed, onAccepted = {
            AndroidSchedulers.mainThread().scheduleDirect {
                if (state.isCurrent(confirmed)) notify(R.string.market_order_accepted)
            }.addTo(bag)
        }, reconfirm = reconfirm) ?: return

        close.map { R.string.cleared_all_positions }
            .onErrorReturn { error ->
                when (error) {
                    is OrderAcceptanceUncertainException -> R.string.order_acceptance_uncertain
                    is ClosePositionService.InitialOrderSubmissionException -> R.string.market_order_failed
                    else -> R.string.close_positions_incomplete
                }
            }
            .startLoading()
            .subscribeNext { message ->
                currentStatusService.updateStatus()
                notify(message)
            }
            .addTo(bag)
    }

    private fun submit(side: String, size: Double) {
        if (isLoading.value || !CryptoCfdOrderSize.isValid(size, maximumOrderSize.value)) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, size)
        val state = currentStatusService.privateState
        val order = state.prepareOrder(request) ?: return
        val submission = state.submitOrder(order) ?: return
        submission.startLoading()
            .subscribe({
                notify(R.string.market_order_accepted)
                currentStatusService.updateStatus()
            }, { error ->
                val message = if (error is OrderAcceptanceUncertainException) R.string.order_acceptance_uncertain
                else R.string.market_order_failed
                notify(message)
            })
            .addTo(bag)
    }
}
