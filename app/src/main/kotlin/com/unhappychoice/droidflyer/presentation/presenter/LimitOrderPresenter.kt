package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.subscribeNextWithSnackBar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.NewOrder
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.presenter.core.Loadable
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.ViewPresenter

class LimitOrderPresenter internal constructor(private val state: PrivateTradingState) : ViewPresenter<LimitOrderView>(), HasAmount, Loadable {
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

    internal fun prepareOrder(price: Long, side: String): NewOrder? {
        if (!canSubmitOrder) return null
        return state.prepareOrder(SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, Math.abs(amount.value)))
    }

    internal fun submitOrder(order: NewOrder) {
        if (isLoading.value) return
        val orderView = view ?: return
        val submission = state.submitOrder(order) ?: return
        val request = order.request
        val messageRes = if (request.side == "BUY") R.string.ordered_buy else R.string.ordered_sell
        val message = orderView.context.getString(messageRes, request.size, request.price)

        isLoading.value = true
        submission.doFinally { isLoading.value = false }
            .subscribeNextWithSnackBar(orderView, message)
            .addTo(bag)
    }
}
