package com.unhappychoice.droidflyer.presentation.view.core

import android.view.View
import android.widget.TextView
import com.jakewharton.rxbinding4.view.clicks as rxBindingClicks
import com.jakewharton.rxbinding4.widget.textChanges as rxBindingTextChanges
import io.reactivex.rxjava3.core.Observable

internal fun View.clicks(): Observable<Unit> = rxBindingClicks()

internal fun TextView.textChanges(): Observable<CharSequence> = rxBindingTextChanges()
