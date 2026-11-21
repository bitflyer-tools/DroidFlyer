package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

data class ChildOrderEvent(
    val productCode: String,
    val childOrderId: String,
    val childOrderAcceptanceId: String,
    val eventDate: String,
    val eventType: String,
    val childOrderType: String?,
    val expireDate: String?,
    val reason: String?,
    val execId: Long?,
    val side: String?,
    val price: Long?,
    val size: Double?,
    val commission: Double?,
    val sfd: Double?
) {
    fun isExecution(): Boolean = eventType == "EXECUTION"

    internal fun affectsStopOrderStatus(): Boolean = eventType in stopOrderStatusEvents

    private companion object {
        val stopOrderStatusEvents = setOf("EXECUTION", "ORDER_FAILED", "CANCEL", "EXPIRE")
    }
}
