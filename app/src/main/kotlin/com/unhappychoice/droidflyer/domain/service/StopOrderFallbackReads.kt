package com.unhappychoice.droidflyer.domain.service

import io.reactivex.Observable
import io.reactivex.Scheduler
import java.util.concurrent.TimeUnit.MILLISECONDS

internal class StopOrderFallbackReads(private val scheduler: Scheduler) {
    private var nextReadAt = Long.MIN_VALUE

    fun <T : Any> read(request: () -> Observable<T>): Observable<T> = Observable.defer {
        val delay = synchronized(this) {
            val now = scheduler.now(MILLISECONDS)
            if (now >= nextReadAt) {
                // Two status timers can each make 200 requests per five minutes.
                // Leave at least 25 requests for interactive traffic under the 500-request limit.
                nextReadAt = now + 4_000
                0L
            } else {
                nextReadAt - now
            }
        }
        if (delay == 0L) request() else {
            Observable.timer(delay, MILLISECONDS, scheduler).concatMap { read(request) }
        }
    }
}
