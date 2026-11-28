package com.unhappychoice.droidflyer.presentation.presenter.core

import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.extension.Variable
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables

interface OrderEntryGate : HasAmount, Loadable {
    val privateClientAvailable: Observable<Boolean>
    val maximumOrderSize: Variable<Double>

    val orderEntryEnabled: Observable<Boolean>
        get() = Observables.combineLatest(
            privateClientAvailable,
            isLoading.asObservable(),
            amount.asObservable(),
            maximumOrderSize.asObservable(),
            ::isOrderEntryEnabled
        ).distinctUntilChanged()

    fun isOrderEntryEnabled(available: Boolean, loading: Boolean, amount: Double, maximum: Double): Boolean =
        available && !loading && CryptoCfdOrderSize.isValid(amount, maximum)
}
