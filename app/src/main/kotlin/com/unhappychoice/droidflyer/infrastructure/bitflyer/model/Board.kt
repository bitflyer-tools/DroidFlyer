package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class Board(
    val midPrice: Double,
    val bids: List<Order>,
    val asks: List<Order>
)
