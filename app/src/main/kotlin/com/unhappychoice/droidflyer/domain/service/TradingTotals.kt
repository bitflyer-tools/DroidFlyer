package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables

data class TradingTotals(val profit: Long, val spread: Long, val balance: Long?)

internal data class Collateral(val amount: Double?)

internal fun observeTradingTotals(
    prices: Observable<Double>,
    positions: Observable<List<Position>>,
    collateral: Observable<Collateral>
): Observable<TradingTotals> = Observables.combineLatest(prices, positions, collateral) { price, position, margin ->
    val currentPrice = price.toLong()
    val profit = position.profit(currentPrice).toLong()
    val balance = margin.amount?.let { it.toLong() + profit }
    TradingTotals(profit, currentPrice - position.average().toLong(), balance)
}
