package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.support.v4.view.PagerAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.github.salomonbrys.kodein.Kodein
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView

class OrderViewPagerAdapter(val context: Context, val kodein: Kodein) : PagerAdapter() {
    override fun isViewFromObject(view: View?, `object`: Any?): Boolean = view == `object`
    override fun getCount(): Int = 2
    override fun instantiateItem(container: ViewGroup?, position: Int): Any? {
        return when (position) {
            0 -> createMarketOrderView(container)
            else -> createMarketOrderView(container)
        }
    }

    override fun destroyItem(container: ViewGroup?, position: Int, `object`: Any?) {
        container?.removeView(`object` as View)
        super.destroyItem(container, position, `object`)
    }

    private fun createMarketOrderView(container: ViewGroup?): View {
        val view = LayoutInflater.from(context).inflate(R.layout.market_order_view, container, false) as MarketOrderView
        view.injector.inject(kodein)
        container?.addView(view)
        return view
    }
}
