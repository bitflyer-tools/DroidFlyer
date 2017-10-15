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
        val pricesToRemove = orders.filter { it.size == 0.0 }.map(Order::price)
        val ordersToAppend = orders.filter { it.size != 0.0 }

        return filterNot { pricesToRemove.contains(it.price) } + ordersToAppend
    }
}
