package com.unhappychoice.droidflyer.presentation.presenter

import android.content.res.Resources
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.CloseConfirmation
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.OrderAcceptanceUncertainException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.presenter.core.OrderEntryGate
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import mortar.ViewPresenter

class MarketOrderPresenter internal constructor(
    private val state: PrivateTradingState,
    override val maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM),
    private val updateStatus: () -> Unit,
    private val notify: (Int) -> Unit
) : ViewPresenter<MarketOrderView>(), OrderEntryGate {
    constructor(
        currentStatusService: CurrentStatusService,
        maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM),
        notify: (Int) -> Unit
    ) : this(currentStatusService.privateState, maximumOrderSize, currentStatusService::updateStatus, notify)

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

    override val privateClientAvailable = state.observePrivateClientAvailability()

    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun buy() = submit("BUY", amount.value)

    fun sell() = submit("SELL", amount.value)

    internal fun positionCloseOrder(): CloseConfirmation? = state.closeConfirmation()

    internal fun clearPosition(
        confirmed: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit
    ) {
        if (isLoading.value) return
        val close = state.closePosition(confirmed, onAccepted = {
            androidMainThreadScheduler().scheduleDirect {
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
                updateStatus()
                notify(message)
            }
            .addTo(bag)
    }

    private fun submit(side: String, size: Double) {
        if (isLoading.value || !CryptoCfdOrderSize.isValid(size, maximumOrderSize.value)) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, size)
        val order = state.prepareOrder(request) ?: return
        val submission = state.submitOrder(order) ?: return
        submission.startLoading()
            .subscribe({
                notify(R.string.market_order_accepted)
                updateStatus()
            }, { error ->
                val message = if (error is OrderAcceptanceUncertainException) R.string.order_acceptance_uncertain
                else R.string.market_order_failed
                notify(message)
            })
            .addTo(bag)
    }
}
