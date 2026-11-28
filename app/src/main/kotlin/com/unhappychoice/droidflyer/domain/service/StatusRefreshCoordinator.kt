package com.unhappychoice.droidflyer.domain.service

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.SerialDisposable

internal class StatusRefreshCoordinator(private val refresh: () -> Completable) {
    private val lock = Any()
    private val activeRefresh = SerialDisposable()
    private var running = false
    private var pending = false

    fun dispose() = activeRefresh.dispose()

    fun request(followUp: Boolean) {
        synchronized(lock) {
            if (activeRefresh.isDisposed) return
            if (running) {
                pending = pending || followUp
                return
            }
            running = true
        }
        startRefresh()
    }

    private fun startRefresh() {
        Completable.defer { refresh() }
            .doOnSubscribe { activeRefresh.replace(it) }
            .subscribe(::onRefreshFinished) {
                it.printStackTrace()
                onRefreshFinished()
            }
    }

    private fun onRefreshFinished() {
        synchronized(lock) {
            activeRefresh.replace(null)
            if (activeRefresh.isDisposed || !pending) {
                running = false
                return
            }
            pending = false
        }
        startRefresh()
    }
}

internal fun <T : Any> Observable<T>.publishStatus(publish: (T) -> Unit): Completable =
    doOnNext { publish(it) }
        .ignoreElements()
        .doOnError { it.printStackTrace() }
        .onErrorComplete()
