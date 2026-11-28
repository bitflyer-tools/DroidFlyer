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
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ReadStatus
import com.unhappychoice.droidflyer.domain.service.TradingHeaderState
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.subjects.PublishSubject
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Date
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

        Observable.combineLatest(
            currentStatusService.tradingHeader,
            currentStatusService.positionStatus.asObservable(),
            currentStatusService.collateralStatus.asObservable()
        ) { state, positionStatus, collateralStatus -> Triple(state, positionStatus, collateralStatus) }
            .observeOn(androidMainThreadScheduler())
            .subscribeNext { (state, positionStatus, collateralStatus) ->
                renderTradingHeader(state, positionStatus, collateralStatus)
            }
            .addTo(bag)
    }

    private fun renderTradingHeader(state: TradingHeaderState, positionStatus: ReadStatus, collateralStatus: ReadStatus) {
        val (positions, totals) = state
        binding.currentPrice.text = totals.price?.let { "${it.splitByComma()} JPY" } ?: "—"
        binding.position.text = positionSummary(positions, positionStatus)
        val positionsUsable = positionStatus != ReadStatus.UNAVAILABLE
        binding.profit.isVisible = positionsUsable && positions.isNotEmpty()
        binding.profit.showValuation(totals.profit.takeIf { positionsUsable }, positionStatus)
        binding.spread.isVisible = positionsUsable && positions.wholeSize() != 0.0
        binding.spread.showValuation(totals.spread.takeIf { positionsUsable }, positionStatus)
        val balanceStatus = when {
            collateralStatus == ReadStatus.UNAVAILABLE -> collateralStatus
            totals.price != null && totals.balance != null && positionStatus == ReadStatus.STALE -> ReadStatus.STALE
            totals.price != null && !positionsUsable -> ReadStatus.UNAVAILABLE
            else -> collateralStatus
        }
        binding.balance.text = balanceSummary(totals.balance, totals.price, balanceStatus)
    }

    private fun positionSummary(positions: List<Position>, status: ReadStatus): String {
        if (status == ReadStatus.UNAVAILABLE) return context.getString(R.string.position_unavailable)

        val summary = if (positions.isEmpty()) {
            context.getString(R.string.no_position)
        } else {
            val size = positions.wholeSize()
            if (size != 0.0) {
                "${positions.average().toLong().splitByComma()} JPY / $size ɃFX"
            } else {
                val (longs, shorts) = positions.partition { it.coefficient() > 0.0 }
                context.getString(
                    R.string.offsetting_positions, longs.wholeSize().toString(), (-shorts.wholeSize()).toString()
                )
            }
        }
        return if (status == ReadStatus.STALE) context.getString(R.string.stale_value, summary) else summary
    }

    private fun balanceSummary(balance: Long?, price: Long?, status: ReadStatus): String {
        if (status == ReadStatus.UNAVAILABLE) return context.getString(R.string.balance_unavailable)

        val summary = balance?.let {
            val formatted = "${it.splitByComma()} JPY"
            if (price == null) context.getString(R.string.status_collateral, formatted) else formatted
        } ?: context.getString(R.string.balance_unavailable)
        return if (status == ReadStatus.STALE) context.getString(R.string.stale_value, summary) else summary
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

    private fun TextView.showValuation(value: Long?, status: ReadStatus) {
        text = value?.splitByComma()?.let {
            if (status == ReadStatus.STALE) context.getString(R.string.stale_value, it) else it
        } ?: "—"
        val color = when {
            value == null -> DefaultStyle.accentColor
            value >= 0L -> DefaultStyle.greenColor
            else -> DefaultStyle.redColor
        }
        setTextColor(color)
    }
}
