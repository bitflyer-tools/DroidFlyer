package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.norimaki.extension.subscribeNext
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.order_view.view.*

class OrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: OrderPresenter by instance()
    val currentStatusService: CurrentStatusService by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        currentStatusService.currentPrice.asObservable()
            .subscribeNext { currentPrice.text = "${it.toLong().splitByComma()} JPY" }
            .addTo(bag)

        currentStatusService.currentPrice.asObservable()
            .subscribeNext {
                profit.text = currentStatusService.profit().splitByComma()
                profit.setCoefficientColor(currentStatusService.profit())

                spread.text = currentStatusService.spread().splitByComma()
                spread.setCoefficientColor(currentStatusService.spread())

                balance.text = "${(currentStatusService.balance.value.toLong() + currentStatusService.profit()).splitByComma()} JPY"
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext {
                when(Math.abs(it.wholeSize())) {
                    0.0 -> {
                        position.text = "No position"
                        profit.visibility = View.GONE
                        spread.visibility = View.GONE
                    }
                    else -> {
                        position.text = "${it.average().toLong().splitByComma()} JPY / ${it.wholeSize()} ɃFX"
                        profit.visibility = View.VISIBLE
                        spread.visibility = View.VISIBLE
                    }
                }
            }.addTo(bag)

        currentStatusService.buyPrice.asObservable()
            .subscribeNext { buyPrice.text = it.splitByComma() }
            .addTo(bag)

        currentStatusService.sellPrice.asObservable()
            .subscribeNext { sellPrice.text = it.splitByComma() }
            .addTo(bag)

        presenter.amount.asObservable()
            .subscribeNext { amount.setText(it.toString()) }
            .addTo(bag)

        presenter.size.asObservable()
            .subscribeNext {
                dotOneButton.alpha = 0.4f
                dotFiveButton.alpha = 0.4f
                oneButton.alpha = 0.4f
                fiveButton.alpha = 0.4f
                tenButton.alpha = 0.4f
                when(it) {
                    0.1 -> dotOneButton.alpha = 1.0f
                    0.5 -> dotFiveButton.alpha = 1.0f
                    1.0 -> oneButton.alpha = 1.0f
                    5.0 -> fiveButton.alpha = 1.0f
                    10.0 -> tenButton.alpha = 1.0f
                }
            }.addTo(bag)

        presenter.isLoading.asObservable()
            .subscribeNext {
                buyButton.isEnabled = !it
                sellButton.isEnabled = !it

                buyButton.alpha = if(it) 0.4f else 1.0f
                sellButton.alpha = if(it) 0.4f else 1.0f
            }.addTo(bag)

        Observables.combineLatest(
            currentStatusService.position.asObservable(),
            presenter.isLoading.asObservable()
        ) { position: List<Position>, isLoading: Boolean -> position.wholeSize() != 0.0 && !isLoading }
            .subscribeNext {
                clearButton.isEnabled = it
                clearButton.alpha = if(it) 1.0f else 0.4f
            }.addTo(bag)

        buyButton.clicks()
            .subscribeNext { presenter.buy() }
            .addTo(bag)

        sellButton.clicks()
            .subscribeNext { presenter.sell() }
            .addTo(bag)

        clearButton.clicks()
            .subscribeNext { presenter.clearPosition() }
            .addTo(bag)

        dotOneButton.clicks()
            .subscribeNext { presenter.size.value = 0.1 }
            .addTo(bag)

        dotFiveButton.clicks()
            .subscribeNext { presenter.size.value = 0.5 }
            .addTo(bag)

        oneButton.clicks()
            .subscribeNext { presenter.size.value = 1.0 }
            .addTo(bag)

        fiveButton.clicks()
            .subscribeNext { presenter.size.value = 5.0 }
            .addTo(bag)

        tenButton.clicks()
            .subscribeNext { presenter.size.value = 10.0 }
            .addTo(bag)

        plusButton.clicks()
            .subscribeNext { presenter.increment() }
            .addTo(bag)

        minusButton.clicks()
            .subscribeNext { presenter.decrement() }
            .addTo(bag)

        amount.textChanges()
            .subscribeNext { presenter.amount.setWithoutEvent(it.toString().toDouble()) }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        header.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        currentPrice.setTextColor(DefaultStyle.accentColor)
        position.setTextColor(DefaultStyle.accentColor)
        balance.setTextColor(DefaultStyle.accentColor)

        dotOneButton.setTextColor(DefaultStyle.accentColor)
        dotFiveButton.setTextColor(DefaultStyle.accentColor)
        oneButton.setTextColor(DefaultStyle.accentColor)
        fiveButton.setTextColor(DefaultStyle.accentColor)
        tenButton.setTextColor(DefaultStyle.accentColor)

        amount.setTextColor(DefaultStyle.accentColor)
        amount.setTextColor(DefaultStyle.accentColor)
        plusButton.setBackgroundColor(DefaultStyle.primaryColor)
        plusButton.setTextColor(DefaultStyle.accentColor)
        minusButton.setBackgroundColor(DefaultStyle.primaryColor)
        minusButton.setTextColor(DefaultStyle.accentColor)

        buyPrice.setTextColor(DefaultStyle.accentColor)
        sellPrice.setTextColor(DefaultStyle.accentColor)
        buyButton.setBackgroundColor(DefaultStyle.buyColor)
        buyButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        sellButton.setBackgroundColor(DefaultStyle.sellColor)
        sellButtonText.setTextColor(DefaultStyle.darkerAccentColor)
        clearButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        clearButton.setTextColor(DefaultStyle.accentColor)
    }

    private fun TextView.setCoefficientColor(price: Long) {
        val color = if (price >= 0L) DefaultStyle.greenColor else DefaultStyle.redColor
        setTextColor(color)
    }
}
