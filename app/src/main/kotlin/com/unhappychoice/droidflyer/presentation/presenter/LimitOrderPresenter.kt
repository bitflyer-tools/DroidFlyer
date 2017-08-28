package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.disposables.CompositeDisposable
import mortar.ViewPresenter

class LimitOrderPresenter : ViewPresenter<LimitOrderView>() {
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }
}