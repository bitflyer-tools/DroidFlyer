package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.CurrentStatusViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
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
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { totals ->
                val positions = currentStatusService.position.value
                binding.profit.renderValuation(totals.profit.takeIf { positions.isNotEmpty() })
                binding.spread.renderValuation(totals.spread.takeIf { positions.wholeSize() != 0.0 })
                binding.balance.text = "${totals.balance.splitByComma()} JPY"
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext { binding.position.text = positionSummary(it) }
            .addTo(bag)
    }

    private fun positionSummary(positions: List<Position>): String {
        if (positions.isEmpty()) return context.getString(R.string.no_position)
        val size = positions.wholeSize()
        if (size != 0.0) return "${positions.average().toLong().splitByComma()} JPY / $size ɃFX"

        val (longs, shorts) = positions.partition { it.coefficient() > 0.0 }
        return context.getString(
            R.string.offsetting_positions, longs.wholeSize().toString(), (-shorts.wholeSize()).toString()
        )
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
