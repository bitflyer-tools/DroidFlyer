package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.support.design.widget.TabLayout
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.support.design.widget.selections
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.norimaki.extension.subscribeNext
import io.reactivex.rxkotlin.addTo
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

        tabLayout.selections()
            .subscribeNext { viewPager.currentItem = tabLayout.selectedTabPosition }
            .addTo(bag)

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
