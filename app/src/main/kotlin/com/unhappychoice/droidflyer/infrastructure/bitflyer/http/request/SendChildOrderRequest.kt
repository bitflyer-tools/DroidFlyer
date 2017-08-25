package com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request

data class SendChildOrderRequest(
    val productCode: String = "BTC_JPY",
    val childOrderType: String,
    val side: String,
    val price: Long?,
    val size: Double,
    val minuteToExpire: Long = 43200,
    val timeInForce: String = "GTC"
)
