package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class ParentOrderEvent(
    val productCode: String,
    val parentOrderId: String,
    val parentOrderAcceptanceId: String,
    val eventDate: String,
    val eventType: String,
    val parentOrderType: String?,
    val reason: String?,
    val childOrderType: String?,
    val parameterIndex: Int?,
    val childOrderAcceptanceId: String?,
    val side: String?,
    val price: Long?,
    val size: Double?,
    val expireDate: String?
)
