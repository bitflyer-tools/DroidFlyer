package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import org.junit.Test

class TradingTotalsTest {
    private val price = Variable(MarketPrice(120.75))
    private val positions = Variable<List<Position>>(emptyList())
    private val collateral = Variable(1000.75)
    private val totals = observeTradingTotals(
        price.asObservable(), positions.asObservable(), collateral.asObservable()
    ).test()

    private val position = Position("FX_BTC_JPY", "BUY", 100L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0)

    @Test
    fun positionUpdateRecalculatesTotalsAtFixedPrice() {
        positions.value = listOf(position)

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(10L, 20L, 1010L)
        )
    }

    @Test
    fun collateralUpdateRecalculatesBalanceAtFixedPrice() {
        positions.value = listOf(position)
        collateral.value = 1500.75

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(10L, 20L, 1010L),
            TradingTotals(10L, 20L, 1510L)
        )
    }

    @Test
    fun priceUpdateUsesLatestPositionAndCollateral() {
        positions.value = listOf(position.copy(price = 110L))
        collateral.value = 1500.75
        price.value = MarketPrice(99.75)

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(5L, 10L, 1005L),
            TradingTotals(5L, 10L, 1505L),
            TradingTotals(-5L, -11L, 1495L)
        )
    }
}
