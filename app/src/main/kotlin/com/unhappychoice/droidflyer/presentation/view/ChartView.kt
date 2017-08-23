package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebViewClient
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import kotlinx.android.synthetic.main.chart_view.view.*


class ChartView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: ChartPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        chartWebView.setWebViewClient(WebViewClient())
        chartWebView.settings.javaScriptEnabled = true
        chartWebView.settings.domStorageEnabled = true
        chartWebView.loadUrl("https://embed.cryptowat.ch/bitflyer/btcfxjpy?locale=ja-JP&customColorScheme=%257B%2522bg%2522%253A%25221b1f27%2522%252C%2522text%2522%253A%2522b6b6b6%2522%252C%2522textStrong%2522%253A%2522ffffff%2522%252C%2522textWeak%2522%253A%25227f7f7f%2522%252C%2522short%2522%253A%2522F7694D%2522%252C%2522shortFill%2522%253A%2522F7694D%2522%252C%2522long%2522%253A%2522fbbd2a%2522%252C%2522longFill%2522%253A%25221b1f27%2522%252C%2522cta%2522%253A%25222b3b45%2522%252C%2522ctaHighlight%2522%253A%25222b3b45%2522%252C%2522alert%2522%253A%2522F7694D%2522%257D")
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}