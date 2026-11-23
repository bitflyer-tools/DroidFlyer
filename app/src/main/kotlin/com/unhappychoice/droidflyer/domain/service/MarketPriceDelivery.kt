package com.unhappychoice.droidflyer.domain.service

import io.reactivex.Observable
import io.reactivex.Scheduler
import java.util.concurrent.atomic.AtomicReference

internal fun Observable<MarketPrice>.observeMarketPricesOn(scheduler: Scheduler): Observable<MarketPrice> =
    Observable.defer {
        var pending: PendingMarketPrice? = null
        map { price ->
            val previous = pending
            when {
                price.yen == null -> PendingMarketPrice(price).also { pending = null }
                previous != null && previous.replace(price) -> previous
                else -> PendingMarketPrice(price).also { pending = it }
            }
        }
            // Enqueue each slot once. A disconnect owns its own slot and ends the current price run.
            .distinctUntilChanged()
            .observeOn(scheduler)
            .map { it.take() }
    }

private class PendingMarketPrice(price: MarketPrice) {
    private val value = AtomicReference<MarketPrice?>(price)

    fun replace(price: MarketPrice): Boolean {
        val previous = value.get() ?: return false
        return value.compareAndSet(previous, price)
    }

    fun take(): MarketPrice = checkNotNull(value.getAndSet(null))
}
