package com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request

data class CancelChildOrderRequest(
    val productCode: String = "BTC_JPY",
    val childOrderAcceptanceId: String
)
