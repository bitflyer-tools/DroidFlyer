package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView

class LimitOrderView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: LimitOrderPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
    }
}
