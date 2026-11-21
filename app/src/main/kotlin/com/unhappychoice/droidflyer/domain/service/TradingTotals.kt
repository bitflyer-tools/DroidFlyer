package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

data class TradingTotals(val profit: Long?, val spread: Long?, val balance: Long?)

internal data class MarketPrice(val yen: Double?)

internal data class Collateral(val amount: Double?)

internal fun observeTradingTotals(
    prices: Observable<MarketPrice>,
    positions: Observable<List<Position>>,
    collateral: Observable<Collateral>,
    scheduler: Scheduler = Schedulers.computation()
): Observable<TradingTotals> {
    val entries = positions.map { Entry(it, it.average().toLong(), it.wholeSize() < 0.0) }
    val account = Observables.combineLatest(entries, collateral, ::Account)
    return Observables.combineLatest(prices, account) { price, currentAccount ->
        Valuation(price.yen?.toLong(), currentAccount)
    }
        .publish { latest ->
            val valuationChanges = latest.distinctUntilChanged { it.account to (it.price != null) }.map { Unit }
            val priceWindows = latest.sample(100, TimeUnit.MILLISECONDS, scheduler).map { Unit }
            Observable.merge(valuationChanges, priceWindows)
                .withLatestFrom(latest) { _, valuation -> valuation }
        }
        .distinctUntilChanged()
        .map { it.totals() }
}

private class Entry(val positions: List<Position>, val average: Long, val isShort: Boolean)

private class Account(val entry: Entry, val collateral: Collateral)

private data class Valuation(val price: Long?, val account: Account) {
    fun totals(): TradingTotals {
        val balance = account.collateral.amount?.toLong()
        if (price == null) return TradingTotals(null, null, balance)
        val profit = account.entry.positions.profit(price).toLong()
        val priceChange = price - account.entry.average
        val spread = if (account.entry.isShort) -priceChange else priceChange
        return TradingTotals(profit, spread, balance?.plus(profit))
    }
}
