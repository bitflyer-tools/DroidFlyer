package com.unhappychoice.droidflyer.presentation.presenter.core

import android.view.View
import io.reactivex.disposables.CompositeDisposable
import mortar.MortarScope
import mortar.ViewPresenter

abstract class PresenterNeedsToken<T : View> : ViewPresenter<T>() {
    val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }
}

