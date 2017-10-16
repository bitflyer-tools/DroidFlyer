package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.presentation.view.OrdersView
import io.reactivex.disposables.CompositeDisposable
import mortar.MortarScope
import mortar.ViewPresenter

class OrdersPresenter : ViewPresenter<OrdersView>() {
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }
}