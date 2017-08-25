package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.norimaki.extension.subscribeNext
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.order_view.view.*

class OrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: OrderPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        presenter.currentPrice.asObservable()
            .subscribeNext { currentPrice.text = it.toString() }
            .addTo(bag)

        presenter.position.asObservable()
            .subscribeNext { /* TBD */ }
            .addTo(bag)

        presenter.balance.asObservable()
            .subscribeNext { balance.text = it.toString() }
            .addTo(bag)

        presenter.buyPrice.asObservable()
            .subscribeNext { buyPrice.text = it.toString() }
            .addTo(bag)

        presenter.sellPrice.asObservable()
            .subscribeNext { sellPrice.text = it.toString() }
            .addTo(bag)

        buyButton.clicks()
            .subscribeNext { presenter.buy() }
            .addTo(bag)

        sellButton.clicks()
            .subscribeNext { presenter.sell() }
            .addTo(bag)

        clearButton.clicks()
            .subscribeNext { presenter.clearPosition() }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}