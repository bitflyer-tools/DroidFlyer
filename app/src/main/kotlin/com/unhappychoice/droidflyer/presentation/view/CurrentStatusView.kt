package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
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
            .subscribeNext { price ->
                binding.currentPrice.text = price.yen?.let { "${it.toLong().splitByComma()} JPY" } ?: "—"
            }
            .addTo(bag)

        currentStatusService.tradingTotals
            .throttleLast(100, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { totals ->
                val hasPosition = currentStatusService.position.value.wholeSize() != 0.0
                binding.profit.renderValuation(totals.profit.takeIf { hasPosition })
                binding.spread.renderValuation(totals.spread.takeIf { hasPosition })
                binding.balance.text = "${totals.balance.splitByComma()} JPY"
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext {
                val size = it.wholeSize()
                binding.position.text = if (size == 0.0) {
                    "No position"
                } else {
                    "${it.average().toLong().splitByComma()} JPY / $size ɃFX"
                }
            }.addTo(bag)
    }

    private fun setupStyle() {
        setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.currentPrice.setTextColor(DefaultStyle.accentColor)
        binding.position.setTextColor(DefaultStyle.accentColor)
        binding.balance.setTextColor(DefaultStyle.accentColor)
    }

    private fun TextView.renderValuation(value: Long?) {
        isVisible = value != null
        value?.let {
            text = it.splitByComma()
            setTextColor(if (it >= 0L) DefaultStyle.greenColor else DefaultStyle.redColor)
        }
    }
}
