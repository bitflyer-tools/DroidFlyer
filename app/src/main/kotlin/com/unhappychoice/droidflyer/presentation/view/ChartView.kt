package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebViewClient
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import kotlinx.android.synthetic.main.chart_view.view.*


class ChartView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: ChartPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        chartWebView.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        chartWebView.setWebViewClient(WebViewClient())
        chartWebView.settings.javaScriptEnabled = true
        chartWebView.settings.domStorageEnabled = true
        chartWebView.loadUrl("https://cryptowat.ch/ja-jp/charts/BITFLYER:BTCFX-JPY")
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}