package com.unhappychoice.droidflyer.extension

import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.Subject

fun <T : Any> Observable<T>.subscribeNext(fn: (T) -> Unit): Disposable =
    subscribeBy(
        onNext = { fn(it) },
        onError = { it.printStackTrace() },
        onComplete = {}
    )

fun <T> Observable<T>.subscribeOnIoObserveOnUI(): Observable<T> =
    subscribeOn(Schedulers.io()).observeOn(androidMainThreadScheduler())

fun <T> Observable<T>.subscribeOnComputationObserveOnUI(): Observable<T> =
    subscribeOn(Schedulers.computation()).observeOn(androidMainThreadScheduler())

fun <T> Observable<T?>.filterNotNull(): Observable<T> =
    filter { it != null }.map { it!! }

fun <T : Any> Observable<T>.bindTo(subject: Subject<T>): Disposable = subscribeNext { subject.onNext(it) }
fun <T : Any> Observable<T>.bindTo(variable: Variable<T>): Disposable = subscribeNext { variable.value = it }
