package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import kotlinx.android.synthetic.main.order_view.view.*

class OrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val activity: MainActivity by instance()
    val presenter: OrderPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        header.inject(activity.module)

        tabLayout.addTab(tabLayout.newTab().setText(context.getString(R.string.market)))
        tabLayout.addTab(tabLayout.newTab().setText(context.getString(R.string.limit)))
        tabLayout.addTab(tabLayout.newTab().setText(context.getString(R.string.orders)))

        tabLayout.addOnTabSelectedListener(object: TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewPager.currentItem = tabLayout.selectedTabPosition
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                viewPager.currentItem = tabLayout.selectedTabPosition
            }

            override fun onTabUnselected(p0: TabLayout.Tab?) {

            }
        })

        viewPager.adapter = OrderViewPagerAdapter(context, activity.module)
        viewPager.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(tabLayout))
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        tabLayout.setTabTextColors(DefaultStyle.darkerAccentColor, DefaultStyle.accentColor)
        tabLayout.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
