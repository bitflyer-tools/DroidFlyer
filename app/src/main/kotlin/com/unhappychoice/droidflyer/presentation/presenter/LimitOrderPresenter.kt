package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.subscribeNextWithSnackBar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
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

    fun buy(price: Long) {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "LIMIT", "BUY", price, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNextWithSnackBar(view, view.context.getString(R.string.ordered_buy, Math.abs(amount.value), price))
            .addTo(bag)
    }

    fun sell(price: Long) {
        if (amount.value == 0.0) return
        val request = SendChildOrderRequest("FX_BTC_JPY", "LIMIT", "SELL", price, Math.abs(amount.value))
        apiClient.sendChildOrder(request)
            .subscribeOnIoObserveOnUI()
            .startLoading()
            .subscribeNextWithSnackBar(view, view.context.getString(R.string.ordered_sell, Math.abs(amount.value), price))
            .addTo(bag)
    }
}