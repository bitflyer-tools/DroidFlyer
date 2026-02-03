package com.unhappychoice.droidflyer.extension

import android.view.View
import com.google.android.material.snackbar.Snackbar
import io.reactivex.Observable
import io.reactivex.disposables.Disposable

fun <T> Observable<T>.withNextSnackBar(
    view: View?,
    message: String? = null,
    length: Int = Snackbar.LENGTH_SHORT,
    actionName: String? = null,
    action: ((View?) -> Unit)? = null
): Observable<T> = doOnNext { data ->
    val m = message ?: data.toString()
    makeSnackbar(view, m, length, actionName, action)?.show()
}

fun <T> Observable<T>.withErrorSnackBar(
    view: View?,
    message: String? = null,
    length: Int = Snackbar.LENGTH_SHORT,
    actionName: String? = null,
    action: ((View?) -> Unit)? = null
): Observable<T> = doOnError { e ->
    val m = message ?: e.message ?: "Error"
    makeSnackbar(view, m, length, actionName, action)?.show()
}

fun <T> Observable<T>.subscribeNextWithSnackBar(
    view: View?,
    message: String? = null,
    length: Int = Snackbar.LENGTH_SHORT,
    actionName: String? = null,
    action: ((View?) -> Unit)? = null
): Disposable = subscribe(
    { data ->
        val m = message ?: data.toString()
        makeSnackbar(view, m, length, actionName, action)?.show()
    },
    { /* ignore errors */ }
)

private fun makeSnackbar(
    view: View?,
    message: String,
    length: Int = Snackbar.LENGTH_SHORT,
    actionName: String? = null,
    action: ((View?) -> Unit)? = null
): Snackbar? = view?.let {
    Snackbar.make(it, message, length).apply {
        if (actionName != null && action != null) setAction(actionName, action)
    }
}
