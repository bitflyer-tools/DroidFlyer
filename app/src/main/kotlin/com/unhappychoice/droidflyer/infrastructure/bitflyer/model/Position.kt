package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import java.util.*

data class Position(
    val productCode: String,
    val side: String,
    val price: Long,
    val size: Double,
    val commission: Double,
    val swapPointAccumulate: Double,
    val requireCollateral: Double,
    val openDate: Date,
    val leverage: Long,
    val pnl: Double
)
