package com.unhappychoice.droidflyer.presentation.presenter.core

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.round

interface HasAmount {
    val unitSize: Variable<Double>
    val amount: Variable<Double>

    fun incrementAmountByUnitSize() {
        amount.value = (amount.value + unitSize.value).round(8)
    }

    fun decrementAmountByUnitSize() {
        amount.value = (amount.value - unitSize.value).round(8)
        if (amount.value <= 0) amount.value = 0.0
    }
}
