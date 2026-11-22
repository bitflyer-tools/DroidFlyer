package com.unhappychoice.droidflyer.presentation.presenter

import android.content.res.Resources
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
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

class LimitOrderPresenter(
    val apiClient: APIClientV1,
    private val resources: Resources,
    private val showSnackbar: (String, Int) -> Unit
) : ViewPresenter<LimitOrderView>(), HasAmount, Loadable {
    override val isLoading = Variable(false)
    override val amount = Variable(0.0)
    override val unitSize = Variable(1.0)

    val groupingSize = Variable(1L)

    val canSubmitOrder: Boolean
        get() = !isLoading.value && CryptoCfdOrderSize.isValid(amount.value)

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

    internal fun submitOrder(terms: LimitOrderTerms) {
        if (isLoading.value || !CryptoCfdOrderSize.isValid(terms.size)) return

        val (side, price, size) = terms
        val request = SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side.name, price, size)

        isLoading.value = true
        apiClient.submitChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .doFinally { isLoading.value = false }
            .subscribe({
                showSnackbar(resources.getString(side.orderedMessage, size, price), Snackbar.LENGTH_SHORT)
            }, { error ->
                val messageResId = if (error is OrderAcceptanceUncertainException) R.string.order_acceptance_uncertain
                else R.string.limit_order_failed
                showSnackbar(resources.getString(messageResId), Snackbar.LENGTH_LONG)
            })
            .addTo(bag)
    }
}

internal data class LimitOrderTerms(val side: LimitOrderSide, val price: Long, val size: Double)

internal enum class LimitOrderSide(val label: Int, val orderedMessage: Int) {
    BUY(R.string.buy, R.string.ordered_buy),
    SELL(R.string.sell, R.string.ordered_sell)
}
