package com.unhappychoice.droidflyer.presentation.view.core

import android.view.View
import android.widget.TextView
import com.jakewharton.rxbinding4.view.clicks as rxBindingClicks
import com.jakewharton.rxbinding4.widget.textChanges as rxBindingTextChanges
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV2Observable
import io.reactivex.Observable

internal fun View.clicks(): Observable<Unit> = toV2Observable(rxBindingClicks())

internal fun TextView.textChanges(): Observable<CharSequence> = toV2Observable(rxBindingTextChanges())
