package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.ChartViewBinding
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.disposables.SerialDisposable
import java.text.NumberFormat
import org.kodein.di.instance

class ChartView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: ChartPresenter by instance()

    private val binding by lazy { ChartViewBinding.bind(this) }
    private val request = SerialDisposable()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        binding.chartRetry.setOnClickListener { loadPrices() }
        loadPrices()
    }

    override fun onDetachedFromWindow() {
        request.set(null)
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun loadPrices() {
        binding.chartContent.isVisible = false
        binding.chartError.isVisible = false
        binding.chartLoading.isVisible = true
        request.set(presenter.loadPrices().subscribeOnIoObserveOnUI()
            .subscribe(::showPrices, { showError() }))
    }

    private fun showPrices(prices: List<Double>) {
        val format = NumberFormat.getNumberInstance()
        binding.chartPriceRange.text = resources.getString(
            R.string.chart_price_range, format.format(prices.min()), format.format(prices.max())
        )
        binding.priceChart.showPrices(prices)
        binding.chartLoading.isVisible = false
        binding.chartContent.isVisible = true
    }

    private fun showError() {
        binding.chartLoading.isVisible = false
        binding.chartError.isVisible = true
    }
}
