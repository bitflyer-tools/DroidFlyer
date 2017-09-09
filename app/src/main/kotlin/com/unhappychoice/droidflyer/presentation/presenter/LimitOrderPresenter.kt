package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.presentation.presenter.core.HasAmount
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.disposables.CompositeDisposable
import mortar.ViewPresenter

class LimitOrderPresenter : ViewPresenter<LimitOrderView>(), HasAmount {
    override val amount = Variable(0.0)
    override val unitSize = Variable(1.0)

    val groupingSize = Variable(1L)

    private val groupingSizes = listOf(1L, 10L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L)
    private val bag = CompositeDisposable()

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun incrementGroupingSize() {
        val index = groupingSizes.indexOf(groupingSize.value)
        groupingSizes.getOrNull(index + 1)?.let { groupingSize.value = it }
    }

    fun decrementGroupingSize() {
        val index = groupingSizes.indexOf(groupingSize.value)
        groupingSizes.getOrNull(index - 1)?.let { groupingSize.value = it }
    }
}