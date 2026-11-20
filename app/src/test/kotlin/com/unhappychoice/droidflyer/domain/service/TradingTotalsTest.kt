package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.schedulers.TestScheduler
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.Assert.assertEquals
import org.junit.Test

class TradingTotalsTest {
    private val price = Variable(120.75)
    private val positions = Variable<List<Position>>(emptyList())
    private val collateral = Variable(1000.75)
    private val scheduler = TestScheduler()
    private val totals = observeTradingTotals(
        price.asObservable(), positions.asObservable(), collateral.asObservable(), scheduler
    ).test()

    private val position = Position("FX_BTC_JPY", "BUY", 100L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0)

    @Test
    fun positionUpdateUsesLatestPriceBeforeWindowEnds() {
        price.value = 140.75
        positions.value = listOf(position)
        totals.assertValueCount(2)
        scheduler.advanceTimeBy(100, MILLISECONDS)

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(20L, 40L, 1020L)
        )
    }

    @Test
    fun collateralUpdateUsesLatestPriceBeforeWindowEnds() {
        positions.value = listOf(position)
        price.value = 140.75
        collateral.value = 1500.75
        totals.assertValueCount(3)
        scheduler.advanceTimeBy(100, MILLISECONDS)

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(10L, 20L, 1010L),
            TradingTotals(20L, 40L, 1520L)
        )
    }

    @Test
    fun priceUpdateUsesLatestPositionAndCollateral() {
        positions.value = listOf(position.copy(price = 110L))
        collateral.value = 1500.75
        price.value = 99.75
        scheduler.advanceTimeBy(100, MILLISECONDS)

        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(5L, 10L, 1005L),
            TradingTotals(5L, 10L, 1505L),
            TradingTotals(-5L, -11L, 1495L)
        )
    }

    @Test
    fun priceBurstCalculatesProfitOnceAndReusesAverage() {
        val trackedPositions = object : AbstractList<Position>() {
            var traversals = 0
            override val size = 1
            override fun get(index: Int) = position
            override fun iterator(): Iterator<Position> {
                traversals++
                return super.iterator()
            }
        }
        positions.value = trackedPositions
        val initialTraversals = trackedPositions.traversals

        repeat(20) {
            price.value = 121.75 + it
            scheduler.advanceTimeBy(4, MILLISECONDS)
        }
        totals.assertValueCount(2)
        assertEquals(initialTraversals, trackedPositions.traversals)

        scheduler.advanceTimeBy(20, MILLISECONDS)
        totals.assertValues(
            TradingTotals(0L, 120L, 1000L),
            TradingTotals(10L, 20L, 1010L),
            TradingTotals(20L, 40L, 1020L)
        )
        assertEquals(initialTraversals + 1, trackedPositions.traversals)
    }
}
