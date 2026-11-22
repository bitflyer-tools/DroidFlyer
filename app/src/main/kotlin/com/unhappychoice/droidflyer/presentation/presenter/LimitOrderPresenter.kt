package com.unhappychoice.droidflyer.presentation.presenter

import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.NewOrder
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.OrderAcceptanceUncertainException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class LimitOrderPresenter internal constructor(
    private val state: PrivateTradingState,
    accountPreference: CryptoCfdAccountPreference
) : ViewPresenter<LimitOrderView>(), HasAmount, Loadable {
    override val isLoading = Variable(false)
    override val amount = Variable(0.0)
    override val unitSize = Variable(1.0)

    val groupingSize = Variable(1L)

    internal val maximumOrderSize = accountPreference.maximumOrderSize

    val canSubmitOrder: Boolean
        get() = !isLoading.value && CryptoCfdOrderSize.isValid(amount.value, maximumOrderSize.value)

    private val groupingSizes = listOf(1L, 10L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L)
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun incrementGroupingSize() {
        val index = groupingSizes.indexOf(groupingSize.value)
        groupingSizes.getOrNull(index + 1)?.let { groupingSize.value = it }
    }

    fun decrementGroupingSize() {
        val index = groupingSizes.indexOf(groupingSize.value)
        groupingSizes.getOrNull(index - 1)?.let { groupingSize.value = it }
    }

    internal fun prepareOrder(side: LimitOrderSide, price: Long): LimitOrderConfirmation? {
        if (!canSubmitOrder) return null
        val request = SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side.name, price, amount.value)
        return state.prepareOrder(request)?.let { LimitOrderConfirmation(side, it) }
    }

    internal fun submitOrder(confirmation: LimitOrderConfirmation) {
        val order = confirmation.order
        val request = order.request
        if (isLoading.value || !CryptoCfdOrderSize.isValid(request.size, maximumOrderSize.value)) return
        val orderView = view ?: return
        val submission = state.submitOrder(order) ?: return
        val message = orderView.context.getString(confirmation.side.orderedMessage, request.size, request.price)

        isLoading.value = true
        submission.doFinally { isLoading.value = false }
            .subscribe({
                Snackbar.make(orderView, message, Snackbar.LENGTH_SHORT).show()
            }, { error ->
                val messageResId = if (error is OrderAcceptanceUncertainException) R.string.order_acceptance_uncertain
                else R.string.limit_order_failed
                Snackbar.make(orderView, messageResId, Snackbar.LENGTH_LONG).show()
            })
            .addTo(bag)
    }
}

internal class LimitOrderConfirmation(val side: LimitOrderSide, val order: NewOrder)

internal enum class LimitOrderSide(val label: Int, val orderedMessage: Int) {
    BUY(R.string.buy, R.string.ordered_buy),
    SELL(R.string.sell, R.string.ordered_sell)
}
