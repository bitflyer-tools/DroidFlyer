package com.unhappychoice.droidflyer.presentation.presenter.core

import com.unhappychoice.droidflyer.extension.Variable
import io.reactivex.Observable

interface Refreshable {
    val isRefreshing: Variable<Boolean>
    fun refresh()

    fun <T> Observable<T>.startRefresh(): Observable<T> {
        isRefreshing.value = true
        return this
            .doOnError { isRefreshing.value = false }
            .doOnComplete { isRefreshing.value = false }
    }
}