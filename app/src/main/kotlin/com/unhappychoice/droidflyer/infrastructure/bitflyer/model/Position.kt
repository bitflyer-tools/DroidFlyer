package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.unhappychoice.droidflyer.extension.round

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
    fun coefficient(): Double = when (side) {
        "BUY" -> 1.0
        "SELL" -> -1.0
        else -> 1.0
    }
}

fun List<Position>.profit(currentPrice: Long): Double = profitAt(currentPrice).round(8)

fun List<Position>.average(): Double {
    val netSize = signedSize()
    if (netSize.round(8) == 0.0) return 0.0

    val referencePrice = first().price
    val signedNotionalDifference = -profitAt(referencePrice)
    return (referencePrice + signedNotionalDifference / netSize).round(8)
}

fun List<Position>.sum(): Double = fold(0.0) { sum, position -> sum + position.price * position.size }.round(8)

fun List<Position>.wholeSize(): Double = signedSize().round(8)

private fun List<Position>.profitAt(currentPrice: Long): Double =
    fold(0.0) { sum, position -> sum + (currentPrice - position.price) * position.size * position.coefficient() }

private fun List<Position>.signedSize(): Double =
    fold(0.0) { sum, position -> sum + position.size * position.coefficient() }
