package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class Board(
    val midPrice: Double,
    val bids: List<Order>,
    val asks: List<Order>
) {
    fun merge(board: Board): Board = copy(
        bids = bids.merge(board.bids),
        asks = asks.merge(board.asks)
    )

    private fun List<Order>.merge(orders: List<Order>): List<Order> {
        val updatesByPrice = orders.associateBy(Order::price)

        return filterNot { it.price in updatesByPrice } + updatesByPrice.values.filter { it.size != 0.0 }
    }
}
