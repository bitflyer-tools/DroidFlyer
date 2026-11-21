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
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class CurrentStatusView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val currentStatusService: CurrentStatusService by instance()

    private val binding by lazy {
        CurrentStatusViewBinding.inflate(LayoutInflater.from(context), this, true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        currentStatusService.tradingTotals
            .subscribeNext { totals ->
                binding.currentPrice.text = totals.price?.let { "${it.splitByComma()} JPY" } ?: "—"
                binding.profit.showValuation(totals.profit)
                binding.spread.showValuation(totals.spread)
                val balance = "${totals.balance.splitByComma()} JPY"
                binding.balance.text = if (totals.price == null) {
                    context.getString(R.string.status_collateral, balance)
                } else {
                    balance
                }
            }.addTo(bag)

        currentStatusService.position.asObservable()
            .subscribeNext { positions ->
                binding.profit.isVisible = positions.isNotEmpty()
                binding.spread.isVisible = positions.wholeSize() != 0.0
                binding.position.text = positionSummary(positions)
            }
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

    private fun TextView.showValuation(value: Long?) {
        text = value?.splitByComma() ?: "—"
        val color = when {
            value == null -> DefaultStyle.accentColor
            value >= 0L -> DefaultStyle.greenColor
            else -> DefaultStyle.redColor
        }
        setTextColor(color)
    }
}
