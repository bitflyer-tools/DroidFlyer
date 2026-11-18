package com.unhappychoice.droidflyer.presentation.view.core

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import io.reactivex.disposables.CompositeDisposable
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.DITrigger

abstract class BaseView(context: Context?, attr: AttributeSet?) : LinearLayout(context, attr), DIAware {
    final override lateinit var di: DI
        private set
    final override val diTrigger = DITrigger()
    protected val bag = CompositeDisposable()

    fun inject(di: DI) {
        if (this::di.isInitialized) return
        this.di = di
        diTrigger.trigger()
    }

    override fun onDetachedFromWindow() {
        bag.dispose()
        super.onDetachedFromWindow()
    }
}
