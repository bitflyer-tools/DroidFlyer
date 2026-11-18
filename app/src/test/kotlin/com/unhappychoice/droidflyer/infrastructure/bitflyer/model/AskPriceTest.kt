package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AskPriceTest {
    @Test
    fun preservesPricesAtOneYenGrouping() {
        listOf(100L, 101L, Long.MAX_VALUE).forEach { price ->
            assertCeiledPrices(price, 1L, price)
        }
    }

    @Test
    fun roundsOnlyOffBucketPricesAtTenYenGrouping() {
        assertCeiledPrices(100L, 10L, 100L)
        assertCeiledPrices(101L, 10L, 110L)
    }

    private fun assertCeiledPrices(price: Long, groupSize: Long, expected: Long) {
        val ask = Order(price, 1.0)
        val childOrder = ChildOrder(
            id = 1,
            childOrderId = "order",
            productCode = "FX_BTC_JPY",
            side = "SELL",
            childOrderType = "LIMIT",
            price = price,
            average_price = 0L,
            size = 1.0,
            childOrderState = "ACTIVE",
            childOrderAcceptanceId = "acceptance",
            outstandingSize = 1.0,
            cancelSize = 0.0,
            executedSize = 0.0,
            totalCommission = 0.0
        )

        assertEquals(expected, ask.ceiledPrice(groupSize))
        assertEquals(expected, childOrder.ceiledPrice(groupSize))
    }
}
