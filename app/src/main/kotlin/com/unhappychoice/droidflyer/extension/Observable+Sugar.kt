package com.unhappychoice.droidflyer.extension

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.Subject

fun <T : Any> Observable<T>.subscribeNext(fn: (T) -> Unit): Disposable =
    subscribeBy(
        onNext = { fn(it) },
        onError = { it.printStackTrace() },
        onComplete = {}
    )

fun <T : Any> Observable<T>.subscribeOnIoObserveOnUI(): Observable<T> =
    subscribeOn(Schedulers.io()).observeOn(androidMainThreadScheduler())

fun <T : Any> Observable<T>.subscribeOnComputationObserveOnUI(): Observable<T> =
    subscribeOn(Schedulers.computation()).observeOn(androidMainThreadScheduler())

fun <T : Any> Observable<T>.bindTo(subject: Subject<T>): Disposable = subscribeNext { subject.onNext(it) }
fun <T : Any> Observable<T>.bindTo(variable: Variable<T>): Disposable = subscribeNext { variable.value = it }
