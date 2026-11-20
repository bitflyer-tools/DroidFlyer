package com.unhappychoice.droidflyer.presentation.adapter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardTypeTest {
    @Test
    fun partiallyFilledBuyAppearsOnlyOnBidAtSharedGroupedPrice() {
        val orders = listOf(activeOrder("BUY", 100, 0.04))

        assertEquals(0.04, BoardType.Bid.personalOutstandingSizes(orders, 10)[bidPrice]!!, 1e-9)
        assertNull(BoardType.Ask.personalOutstandingSizes(orders, 10)[askPrice])
    }

    @Test
    fun partiallyFilledSellAppearsOnlyOnAskAtSharedGroupedPrice() {
        val orders = listOf(activeOrder("SELL", 99, 0.06))

        assertEquals(0.06, BoardType.Ask.personalOutstandingSizes(orders, 10)[askPrice]!!, 1e-9)
        assertNull(BoardType.Bid.personalOutstandingSizes(orders, 10)[bidPrice])
    }

    @Test
    fun sumsRemainingSizesWithinEachSideAndPriceBucket() {
        val orders = listOf(
            activeOrder("BUY", 99, 0.01),
            activeOrder("BUY", 101, 0.04),
            activeOrder("BUY", 109, 0.02),
            activeOrder("BUY", 110, 0.08),
            activeOrder("SELL", 91, 0.03),
            activeOrder("SELL", 99, 0.02),
            activeOrder("SELL", 101, 0.07)
        )

        assertEquals(0.06, BoardType.Bid.personalOutstandingSizes(orders, 10)[bidPrice]!!, 1e-9)
        assertEquals(0.05, BoardType.Ask.personalOutstandingSizes(orders, 10)[askPrice]!!, 1e-9)
    }

    private val askPrice = BoardType.Ask.groupBySize(listOf(Order(99, 1.0)), 10).single().price
    private val bidPrice = BoardType.Bid.groupBySize(listOf(Order(101, 1.0)), 10).single().price
}

internal fun activeOrder(side: String, price: Long, outstandingSize: Double) = ChildOrder(
    id = 1,
    childOrderId = "order",
    productCode = "BTC_JPY",
    side = side,
    childOrderType = "LIMIT",
    price = price,
    average_price = price,
    size = 0.10,
    childOrderState = "ACTIVE",
    childOrderAcceptanceId = "acceptance",
    outstandingSize = outstandingSize,
    cancelSize = 0.0,
    executedSize = 0.10 - outstandingSize,
    totalCommission = 0.0
)
