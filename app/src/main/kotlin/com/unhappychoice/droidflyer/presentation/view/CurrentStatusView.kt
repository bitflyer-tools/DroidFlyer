package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.CurrentStatusViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.FundingStatus
import com.unhappychoice.droidflyer.domain.service.TradingHeaderState
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnComputationObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import org.kodein.di.instance

class CurrentStatusView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val currentStatusService: CurrentStatusService by instance()

    private val fundingVisibility = PublishSubject.create<Boolean>()

    private val binding by lazy {
        CurrentStatusViewBinding.inflate(LayoutInflater.from(context), this, true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupStyle()

        currentStatusService.observeFunding(fundingVisibility)
            .subscribeNext(::renderFunding)
            .addTo(bag)
        updateFundingVisibility()

        currentStatusService.currentPrice.asObservable()
            .throttleLast(100, TimeUnit.MILLISECONDS)
            .subscribeOnComputationObserveOnUI()
            .subscribeNext { price ->
                binding.currentPrice.text = price.yen?.let { "${it.toLong().splitByComma()} JPY" } ?: "—"
            }
            .addTo(bag)

        currentStatusService.tradingHeader
            .subscribeOnComputationObserveOnUI()
            .subscribeNext(::renderTradingHeader)
            .addTo(bag)
    }

    private fun renderTradingHeader(state: TradingHeaderState) {
        binding.position.text = positionSummary(state.positions)
        binding.profit.renderValuation(state.profit)
        binding.spread.renderValuation(state.spread)
        binding.balance.text = state.balance?.let { "${it.splitByComma()} JPY" } ?: "Balance unavailable"
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateFundingVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateFundingVisibility()
    }

    private fun updateFundingVisibility() {
        if (isAttachedToWindow) fundingVisibility.onNext(isShown && windowVisibility == View.VISIBLE)
    }

    private fun renderFunding(status: FundingStatus) {
        val funding = status.funding
        if (funding == null) {
            binding.funding.text = context.getString(R.string.funding_unavailable)
            return
        }

        val percent = NumberFormat.getPercentInstance().apply {
            maximumFractionDigits = 10
            if (funding.ratio.signum() > 0 && this is DecimalFormat) positivePrefix = "+${positivePrefix}"
        }.format(funding.ratio)
        val settlement = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(funding.settlementMillis))
        val details = context.getString(R.string.funding_details, percent, settlement)
        binding.funding.text = if (status.stale) context.getString(R.string.funding_stale, details) else details
    }

    private fun setupStyle() {
        setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.currentPrice.setTextColor(DefaultStyle.accentColor)
        binding.position.setTextColor(DefaultStyle.accentColor)
        binding.balance.setTextColor(DefaultStyle.accentColor)
        binding.funding.setTextColor(DefaultStyle.accentColor)
    }

    private fun TextView.renderValuation(value: Long?) {
        isVisible = value != null
        value?.let {
            text = it.splitByComma()
            setTextColor(if (it >= 0L) DefaultStyle.greenColor else DefaultStyle.redColor)
        }
    }
}
