package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.market_order_view.view.*

class MarketOrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: MarketOrderPresenter by instance()
    val currentStatusService: CurrentStatusService by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        currentStatusService.buyPrice.asObservable()
            .subscribeNext { buyPrice.text = it.splitByComma() }
            .addTo(bag)

        currentStatusService.sellPrice.asObservable()
            .subscribeNext { sellPrice.text = it.splitByComma() }
            .addTo(bag)

        presenter.amount.asObservable()
            .bindTo(amountSelector.amount)
            .addTo(bag)

        presenter.size.asObservable()
            .bindTo(amountSelector.size)
            .addTo(bag)

        Observables.combineLatest(
            presenter.amount.asObservable(),
            presenter.isLoading.asObservable()
        ) { amount, isLoading -> amount != 0.0 && !isLoading }
            .subscribeNext {
                buyButton.isEnabled = it
                sellButton.isEnabled = it

                buyButton.alpha = if (it) 1.0f else 0.4f
                sellButton.alpha = if (it) 1.0f else 0.4f
            }.addTo(bag)

        Observables.combineLatest(
            currentStatusService.position.asObservable(),
            presenter.isLoading.asObservable()
        ) { position: List<Position>, isLoading: Boolean -> position.wholeSize() != 0.0 && !isLoading }
            .subscribeNext {
                clearButton.isEnabled = it
                clearButton.alpha = if (it) 1.0f else 0.4f
            }.addTo(bag)

        amountSelector.amount.asObservable()
            .subscribeNext { presenter.amount.setWithoutEvent(it) }
            .addTo(bag)

        amountSelector.size.asObservable()
            .subscribeNext { presenter.size.setWithoutEvent(it) }
            .addTo(bag)

        amountSelector.didIncrement
            .subscribeNext { presenter.increment() }
            .addTo(bag)

        amountSelector.didDecrement
            .subscribeNext { presenter.decrement() }
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

    private fun setupStyle() {
        buyPrice.setTextColor(DefaultStyle.accentColor)
        sellPrice.setTextColor(DefaultStyle.accentColor)
        buyButton.setBackgroundColor(DefaultStyle.buyColor)
        buyButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        sellButton.setBackgroundColor(DefaultStyle.sellColor)
        sellButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        clearButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        clearButton.setTextColor(DefaultStyle.accentColor)
    }
}
