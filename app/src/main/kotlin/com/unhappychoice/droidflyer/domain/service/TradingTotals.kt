package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

data class TradingTotals(val profit: Long, val spread: Long, val balance: Long?)

internal data class Collateral(val amount: Double?)

internal fun observeTradingTotals(
    prices: Observable<Double>,
    positions: Observable<List<Position>>,
    collateral: Observable<Collateral>,
    scheduler: Scheduler = Schedulers.computation()
): Observable<TradingTotals> {
    val entries = positions.map { Entry(it, it.average().toLong()) }
    val account = Observables.combineLatest(entries, collateral, ::Account)
    return Observables.combineLatest(prices.map { it.toLong() }, account, ::Valuation)
        .publish { latest ->
            val accountChanges = latest.distinctUntilChanged { it.account }.map { Unit }
            val priceWindows = latest.sample(100, TimeUnit.MILLISECONDS, scheduler).map { Unit }
            Observable.merge(accountChanges, priceWindows)
                .withLatestFrom(latest) { _, valuation -> valuation }
        }
        .distinctUntilChanged()
        .map { it.totals() }
}

private class Entry(val positions: List<Position>, val average: Long)

private class Account(val entry: Entry, val collateral: Collateral)

private data class Valuation(val price: Long, val account: Account) {
    fun totals(): TradingTotals {
        val profit = account.entry.positions.profit(price).toLong()
        val balance = account.collateral.amount?.let { it.toLong() + profit }
        return TradingTotals(profit, price - account.entry.average, balance)
    }
}
