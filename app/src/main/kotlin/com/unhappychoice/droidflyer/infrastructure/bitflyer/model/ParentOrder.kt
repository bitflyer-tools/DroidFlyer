package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class ParentOrder(
    val parentOrderId: String,
    val parentOrderAcceptanceId: String,
    val productCode: String,
    val parentOrderType: String,
    val parentOrderState: String,
    val side: String,
    val size: Double,
    val executedSize: Double,
    val id: Long = 0
)

data class ParentOrderDetails(
    val orderMethod: String,
    val parameters: List<ParentOrderParameter>
)

data class ParentOrderParameter(
    val productCode: String,
    val conditionType: String,
    val triggerPrice: Long
)

data class ParentOrderAcceptance(val parentOrderAcceptanceId: String?)
