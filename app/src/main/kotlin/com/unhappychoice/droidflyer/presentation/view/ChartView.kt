package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebViewClient
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.databinding.ChartViewBinding
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView


class ChartView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: ChartPresenter by instance()

    private val binding by lazy {
        ChartViewBinding.bind(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        binding.chartWebView.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.chartWebView.setWebViewClient(WebViewClient())
        binding.chartWebView.settings.javaScriptEnabled = true
        binding.chartWebView.settings.domStorageEnabled = true
        binding.chartWebView.loadUrl("https://cryptowat.ch/ja-jp/charts/BITFLYER:BTCFX-JPY")
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}