package com.unhappychoice.droidflyer.presentation.presenter.core

import com.unhappychoice.droidflyer.extension.Variable
import io.reactivex.rxjava3.core.Observable

interface Loadable {
    val isLoading: Variable<Boolean>

    fun <T : Any> Observable<T>.startLoading(): Observable<T> {
        isLoading.value = true
        return this
            .doOnError { isLoading.value = false }
            .doOnComplete { isLoading.value = false }
    }
}