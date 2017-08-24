package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import java.util.*

data class Ticker(
    val productCode: String,
    val timestamp: Date,
    val tickId: Long,
    val bestBid: Long,
    val bestAsk: Long,
    val bestBidSize: Double,
    val bestAskSize: Double,
    val totalBidDepth: Double,
    val totalAskDepth: Double,
    val ltp: Long,
    val volume: Double,
    val volumeByProduct: Double
)
