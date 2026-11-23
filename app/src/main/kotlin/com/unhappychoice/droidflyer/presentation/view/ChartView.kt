package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.ChartViewBinding
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.presentation.presenter.Candle
import com.unhappychoice.droidflyer.presentation.presenter.CandleHistory
import com.unhappychoice.droidflyer.presentation.presenter.CandleInterval
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.disposables.SerialDisposable
import org.kodein.di.instance
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: ChartPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        binding.chartRetry.setOnClickListener { loadHistory() }
        binding.chartIntervals.check(
            if (presenter.interval == CandleInterval.ONE_MINUTE) R.id.chartOneMinute else R.id.chartFiveMinutes
        )
        binding.chartIntervals.setOnCheckedChangeListener { _, id ->
            presenter.interval = if (id == R.id.chartFiveMinutes) CandleInterval.FIVE_MINUTES else CandleInterval.ONE_MINUTE
            history?.let(::showHistory)
        }
        binding.priceChart.onCandleSelected = ::showCandle
        presenter.cachedHistory()?.let(::showHistory) ?: loadHistory()
    }

    override fun onDetachedFromWindow() {
        request.set(null)
        binding.chartIntervals.setOnCheckedChangeListener(null)
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private val binding by lazy { ChartViewBinding.bind(this) }
    private val request = SerialDisposable()
    private var history: CandleHistory? = null

    private fun loadHistory() {
        history = null
        binding.chartContent.isVisible = false
        binding.chartError.isVisible = false
        binding.chartLoading.isVisible = true
        request.set(presenter.loadHistory().subscribeOnIoObserveOnUI()
            .subscribe(::showHistory, { showError() }))
    }

    private fun showHistory(history: CandleHistory) {
        this.history = history
        val series = history.series(presenter.interval)
        binding.chartPartial.isVisible = series.candles.first().partial
        binding.priceChart.showSeries(series)
        binding.chartLoading.isVisible = false
        binding.chartError.isVisible = false
        binding.chartContent.isVisible = true
    }

    private fun showCandle(candle: Candle?) {
        binding.chartCandleDetails.text = if (candle == null) {
            resources.getString(R.string.chart_no_trades)
        } else {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.getDefault())
            val start = time.format(Date(candle.start))
            val end = time.format(Date(candle.start + presenter.interval.millis))
            val format = NumberFormat.getNumberInstance()
            resources.getString(
                if (candle.partial) R.string.chart_partial_candle else R.string.chart_candle,
                start, end, format.format(candle.open), format.format(candle.high),
                format.format(candle.low), format.format(candle.close)
            )
        }
    }

    private fun showError() {
        binding.chartLoading.isVisible = false
        binding.chartError.isVisible = true
    }
}
