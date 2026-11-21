package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrder

internal data class StopOrder(
    val parent: ParentOrder,
    val triggerPrice: Long?,
    val event: String? = null,
    val triggerVerified: Boolean = true
) {
    val acceptanceId get() = parent.parentOrderAcceptanceId
    val canCancel get() = triggerPrice != null && status == StopOrderStatus.WAITING

    val isPartiallyExecuted get() = parent.executedSize > 0 && parent.executedSize < parent.size

    val status: StopOrderStatus
        get() = when {
            parent.executedSize >= parent.size && parent.size > 0 -> StopOrderStatus.EXECUTED
            parent.parentOrderState == "CANCELED" || event == "CANCEL" -> StopOrderStatus.CANCELED
            parent.parentOrderState == "EXPIRED" || event == "EXPIRE" -> StopOrderStatus.EXPIRED
            isPartiallyExecuted -> StopOrderStatus.PARTIALLY_EXECUTED
            parent.parentOrderState == "REJECTED" || event == "ORDER_FAILED" -> StopOrderStatus.REJECTED
            parent.parentOrderState == "COMPLETED" || event in triggeredEvents -> StopOrderStatus.TRIGGERED
            parent.parentOrderState == "ACTIVE" && triggerVerified -> StopOrderStatus.WAITING
            else -> StopOrderStatus.UNKNOWN
        }

    companion object {
        private val triggeredEvents = setOf("TRIGGER", "COMPLETE")
    }
}

internal enum class StopOrderStatus {
    WAITING, TRIGGERED, PARTIALLY_EXECUTED, EXECUTED, CANCELED, EXPIRED, REJECTED, UNKNOWN
}
