package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.current_status_view.view.*
import java.util.concurrent.TimeUnit

class CurrentStatusView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val currentStatusService: CurrentStatusService by instance()

    init {
        LayoutInflater.from(context).inflate(R.layout.current_status_view, this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        currentStatusService.currentPrice.asObservable()
            .throttleLast(100, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { currentPrice.text = "${it.toLong().splitByComma()} JPY" }
            .addTo(bag)

        currentStatusService.currentPrice.asObservable()
            .throttleLast(100, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext {
                profit.text = currentStatusService.profit().splitByComma()
                profit.setCoefficientColor(currentStatusService.profit())

                spread.text = currentStatusService.spread().splitByComma()
                spread.setCoefficientColor(currentStatusService.spread())

                balance.text = "${(currentStatusService.balance.value.toLong() + currentStatusService.profit()).splitByComma()} JPY"
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext {
                when (Math.abs(it.wholeSize())) {
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
    }

    private fun setupStyle() {
        setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        currentPrice.setTextColor(DefaultStyle.accentColor)
        position.setTextColor(DefaultStyle.accentColor)
        balance.setTextColor(DefaultStyle.accentColor)
    }

    private fun TextView.setCoefficientColor(price: Long) {
        val color = if (price >= 0L) DefaultStyle.greenColor else DefaultStyle.redColor
        setTextColor(color)
    }
}