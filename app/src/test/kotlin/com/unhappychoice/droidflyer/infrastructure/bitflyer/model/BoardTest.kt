package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardTest {
    @Test
    fun repeatedUpdatesReplaceTheQuantityIndependentlyOnEachSide() {
        val board = Board(100.0, listOf(Order(100, 2.0)), listOf(Order(100, 4.0)))
        val firstUpdate = Board(100.0, listOf(Order(100, 3.0)), emptyList())
        val secondUpdate = Board(100.0, listOf(Order(100, 5.0), Order(100, 6.0)), emptyList())

        val firstResult = board.merge(firstUpdate)
        assertEquals(listOf(Order(100, 3.0)), firstResult.bids)

        val result = firstResult.merge(secondUpdate)
        assertEquals(listOf(Order(100, 6.0)), result.bids)
        assertEquals(board.asks, result.asks)
    }

    @Test
    fun zeroSizedUpdateRemovesOnlyTheUpdatedPriceOnEachSide() {
        val board = Board(100.0, listOf(Order(99, 2.0), Order(98, 3.0)), listOf(Order(101, 4.0), Order(102, 5.0)))
        val update = Board(100.0, listOf(Order(99, 0.0)), listOf(Order(101, 6.0), Order(101, 0.0)))

        val result = board.merge(update)
        assertEquals(listOf(Order(98, 3.0)), result.bids)
        assertEquals(listOf(Order(102, 5.0)), result.asks)
    }
}
