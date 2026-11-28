package com.unhappychoice.droidflyer.presentation.view

import io.reactivex.rxjava3.core.Observable

internal fun Observable<CharSequence>.orderAmounts(): Observable<Double> = map { it.toOrderAmount() }

internal fun CharSequence.toOrderAmount(): Double =
    toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
