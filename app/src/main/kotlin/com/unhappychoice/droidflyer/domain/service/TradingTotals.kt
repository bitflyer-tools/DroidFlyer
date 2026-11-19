package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.average
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.profit
import io.reactivex.Observable
import io.reactivex.rxkotlin.Observables

data class TradingTotals(val profit: Long?, val spread: Long?, val balance: Long)

internal data class MarketPrice(val yen: Double?)

internal fun observeTradingTotals(
    prices: Observable<MarketPrice>,
    positions: Observable<List<Position>>,
    collateral: Observable<Double>
): Observable<TradingTotals> = Observables.combineLatest(prices, positions, collateral) { price, position, balance ->
    val currentPrice = price.yen?.toLong()
        ?: return@combineLatest TradingTotals(null, null, balance.toLong())
    val profit = position.profit(currentPrice).toLong()
    TradingTotals(profit, currentPrice - position.average().toLong(), balance.toLong() + profit)
}
