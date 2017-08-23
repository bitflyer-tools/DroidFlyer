package com.unhappychoice.droidflyer.presentation.view.core

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.github.salomonbrys.kodein.KodeinInjected
import com.github.salomonbrys.kodein.KodeinInjector
import io.reactivex.disposables.CompositeDisposable

abstract class BaseView(context: Context?, attr: AttributeSet?) : LinearLayout(context, attr), KodeinInjected {
    override val injector = KodeinInjector()
    protected val bag = CompositeDisposable()

    override fun onDetachedFromWindow() {
        bag.dispose()
        super.onDetachedFromWindow()
    }
}
