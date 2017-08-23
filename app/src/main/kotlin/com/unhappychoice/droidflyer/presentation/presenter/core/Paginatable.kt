package com.unhappychoice.droidflyer.presentation.presenter.core

import com.unhappychoice.droidflyer.extension.Variable
import io.reactivex.Observable

interface Paginatable {
    val page: Variable<Int>
    val hasMore: Variable<Boolean>

    fun <T> Observable<List<T>>.paginate(): Observable<List<T>> = doOnNext {
        hasMore.value = it.isNotEmpty()
        page.value = page.value + 1
    }
}