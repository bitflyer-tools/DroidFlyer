package com.unhappychoice.droidflyer.domain.service

import io.reactivex.Completable
import io.reactivex.Observable

internal class StatusRefreshCoordinator(private val refresh: () -> Completable) {
    private val lock = Any()
    private var running = false
    private var pending = false

    fun request(followUp: Boolean) {
        synchronized(lock) {
            if (running) {
                pending = pending || followUp
                return
            }
            running = true
        }
        startRefresh()
    }

    private fun startRefresh() {
        Completable.defer { refresh() }.subscribe(::onRefreshFinished) {
            it.printStackTrace()
            onRefreshFinished()
        }
    }

    private fun onRefreshFinished() {
        synchronized(lock) {
            if (!pending) {
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
