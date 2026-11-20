package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.unhappychoice.droidflyer.databinding.CurrentStatusViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import java.util.concurrent.TimeUnit
import org.kodein.di.instance

class CurrentStatusView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val currentStatusService: CurrentStatusService by instance()

    private val binding by lazy {
        CurrentStatusViewBinding.inflate(LayoutInflater.from(context), this, true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        currentStatusService.currentPrice.asObservable()
            .throttleLast(100, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { binding.currentPrice.text = "${it.toLong().splitByComma()} JPY" }
            .addTo(bag)

        currentStatusService.tradingTotals
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { totals ->
                binding.profit.text = totals.profit.splitByComma()
                binding.profit.setCoefficientColor(totals.profit)

                binding.spread.text = totals.spread.splitByComma()
                binding.spread.setCoefficientColor(totals.spread)

                binding.balance.text = "${totals.balance.splitByComma()} JPY"
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext {
                when (Math.abs(it.wholeSize())) {
                    0.0 -> {
                        binding.position.text = "No position"
                        binding.profit.visibility = View.GONE
                        binding.spread.visibility = View.GONE
                    }
                    else -> {
                        binding.position.text = "${it.average().toLong().splitByComma()} JPY / ${it.wholeSize()} ɃFX"
                        binding.profit.visibility = View.VISIBLE
                        binding.spread.visibility = View.VISIBLE
                    }
                }
            }.addTo(bag)
    }

    private fun setupStyle() {
        setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.currentPrice.setTextColor(DefaultStyle.accentColor)
        binding.position.setTextColor(DefaultStyle.accentColor)
        binding.balance.setTextColor(DefaultStyle.accentColor)
    }

    private fun TextView.setCoefficientColor(price: Long) {
        val color = if (price >= 0L) DefaultStyle.greenColor else DefaultStyle.redColor
        setTextColor(color)
    }
}
