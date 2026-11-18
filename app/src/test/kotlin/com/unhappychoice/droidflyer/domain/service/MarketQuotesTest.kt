package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import org.junit.Test

class MarketQuotesTest {
    private val buyPrice = Variable(0L)
    private val sellPrice = Variable(0L)
    private val buyUpdates = buyPrice.asObservable().test()
    private val sellUpdates = sellPrice.asObservable().test()
    private val board = Board(
        midPrice = 100.0,
        bids = listOf(Order(98, 1.0), Order(99, 1.0)),
        asks = listOf(Order(102, 1.0), Order(101, 1.0))
    )

    @Test
    fun quantityOnlyUpdatesDoNotEmitQuotes() {
        board.updateMarketQuotes(buyPrice, sellPrice)
        repeat(100) { index ->
            val size = index + 2.0
            board.copy(
                bids = board.bids.map { it.copy(size = size) },
                asks = board.asks.map { it.copy(size = size) }
            ).updateMarketQuotes(buyPrice, sellPrice)
        }

        buyUpdates.assertValues(0L, 101L)
        sellUpdates.assertValues(0L, 99L)
    }

    @Test
    fun eachQuoteEmitsOnlyWhenItsOwnPriceChanges() {
        board.updateMarketQuotes(buyPrice, sellPrice)
        val newAsk = board.copy(asks = listOf(Order(102, 1.0)))
        newAsk.updateMarketQuotes(buyPrice, sellPrice)

        buyUpdates.assertValues(0L, 101L, 102L)
        sellUpdates.assertValues(0L, 99L)

        newAsk.copy(bids = listOf(Order(98, 1.0))).updateMarketQuotes(buyPrice, sellPrice)

        buyUpdates.assertValues(0L, 101L, 102L)
        sellUpdates.assertValues(0L, 99L, 98L)
    }

    @Test
    fun emptyBoardResetsQuotesOnce() {
        board.updateMarketQuotes(buyPrice, sellPrice)
        val emptyBoard = board.copy(bids = emptyList(), asks = emptyList())
        repeat(2) { emptyBoard.updateMarketQuotes(buyPrice, sellPrice) }

        buyUpdates.assertValues(0L, 101L, 0L)
        sellUpdates.assertValues(0L, 99L, 0L)
    }
}
