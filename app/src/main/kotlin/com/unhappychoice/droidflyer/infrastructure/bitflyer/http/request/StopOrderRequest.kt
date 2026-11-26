package com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request

import java.math.BigDecimal

enum class StopSide { BUY, SELL }

class StopOrderRequest private constructor(
    val side: StopSide,
    val size: BigDecimal,
    val triggerPrice: Long
) {
    companion object {
        fun fromInput(side: StopSide, quantity: String, price: String): StopOrderRequest? {
            val size = quantity.trim().toBigDecimalOrNull()
                ?.takeIf { it.signum() > 0 && it.toDouble().let { value -> value.isFinite() && value > 0 } }
                ?: return null
            val triggerPrice = price.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            return StopOrderRequest(side, size, triggerPrice)
        }
    }
}

class SendParentOrderRequest(order: StopOrderRequest) {
    val orderMethod = "SIMPLE"
    val parameters = listOf(Parameter(order.side, order.size, order.triggerPrice))

    class Parameter(val side: StopSide, val size: BigDecimal, val triggerPrice: Long) {
        val productCode = "FX_BTC_JPY"
        val conditionType = "STOP"
    }
}

class CancelParentOrderRequest(val parentOrderAcceptanceId: String) {
    val productCode = "FX_BTC_JPY"
}
