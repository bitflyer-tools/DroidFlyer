package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.OrderViewBinding
import com.unhappychoice.droidflyer.presentation.adapter.OrderViewPagerAdapter
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView

class OrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val activity: MainActivity by instance()
    val presenter: OrderPresenter by instance()
    private val binding by lazy { OrderViewBinding.bind(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        binding.header.inject(activity.module)

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(context.getString(R.string.market)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(context.getString(R.string.limit)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(context.getString(R.string.orders)))

        binding.tabLayout.addOnTabSelectedListener(object: TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                binding.viewPager.currentItem = binding.tabLayout.selectedTabPosition
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                binding.viewPager.currentItem = binding.tabLayout.selectedTabPosition
            }

            override fun onTabUnselected(p0: TabLayout.Tab?) {

            }
        })

        binding.viewPager.adapter = OrderViewPagerAdapter(context, activity.module)
        binding.viewPager.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(binding.tabLayout))
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        binding.tabLayout.setTabTextColors(DefaultStyle.darkerAccentColor, DefaultStyle.accentColor)
        binding.tabLayout.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
