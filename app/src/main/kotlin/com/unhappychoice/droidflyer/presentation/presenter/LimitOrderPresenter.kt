package com.unhappychoice.droidflyer.presentation.presenter

import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.OrderAcceptanceUncertainException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.submitChildOrder
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class LimitOrderPresenter(val apiClient: APIClientV1) : ViewPresenter<LimitOrderView>(), HasAmount, Loadable {
    override val isLoading = Variable(false)
    override val amount = Variable(0.0)
    override val unitSize = Variable(1.0)

    val groupingSize = Variable(1L)

    val canSubmitOrder: Boolean
        get() = !isLoading.value && amount.value != 0.0

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

    fun buy(price: Long) = submitOrder(price, "BUY", R.string.ordered_buy)

    fun sell(price: Long) = submitOrder(price, "SELL", R.string.ordered_sell)

    private fun submitOrder(price: Long, side: String, messageRes: Int) {
        if (!canSubmitOrder) return

        val size = Math.abs(amount.value)
        val request = SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, size)
        val orderView = view
        val message = orderView.context.getString(messageRes, size, price)

        isLoading.value = true
        apiClient.submitChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .doFinally { isLoading.value = false }
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
