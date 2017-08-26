package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.unhappychoice.droidflyer.extension.round
import java.math.BigDecimal
import java.math.RoundingMode

data class Position(
    val productCode: String,
    val side: String,
    val price: Long,
    val size: Double,
    val commission: Double,
    val swapPointAccumulate: Double,
    val requireCollateral: Double,
    val leverage: Long,
    val pnl: Double
) {
    fun coefficient(): Double = when(side) {
        "BUY" -> 1.0
        "SELL" -> -1.0
        else -> 1.0
    }
}

fun List<Position>.profit(currentPrice: Long): Double =
    fold(0.0) { sum, position -> sum + (currentPrice - position.price) * position.size * position.coefficient() }.round(8)

fun List<Position>.average(): Double = (if (wholeSize() != 0.0) (sum() / Math.abs(wholeSize())) else 0.0).round(8)

fun List<Position>.sum(): Double = fold(0.0) { sum, position -> sum + position.price * position.size }.round(8)

fun List<Position>.wholeSize(): Double =  fold(0.0) { sum, position -> sum + position.size * position.coefficient() }.round(8)

