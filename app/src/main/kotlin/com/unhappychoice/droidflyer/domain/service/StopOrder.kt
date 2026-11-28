package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent

internal data class StopOrder(
    val parent: ParentOrder,
    val triggerPrice: Long?,
    val event: String? = null,
    val triggerVerified: Boolean = true,
    val childTermination: StopOrderStatus? = null,
    val waitingConfirmed: Boolean = true,
    val restWaitingConfirmed: Boolean = false,
    val recoveryAvailable: Boolean = false,
    val childVerification: StopChildVerification? = null
) {
    val acceptanceId get() = parent.parentOrderAcceptanceId
    val canCancel get() = triggerPrice != null && status == StopOrderStatus.WAITING

    val canConfirmRecovery get() = recoveryAvailable && triggerPrice != null && triggerVerified &&
        status == StopOrderStatus.UNKNOWN

    val isPartiallyExecuted get() = parent.executedSize > 0 && parent.executedSize < parent.size

    val status: StopOrderStatus
        get() = when {
            parent.executedSize >= parent.size && parent.size > 0 -> StopOrderStatus.EXECUTED
            parent.parentOrderState == "CANCELED" || event == "CANCEL" -> StopOrderStatus.CANCELED
            parent.parentOrderState == "EXPIRED" || event == "EXPIRE" -> StopOrderStatus.EXPIRED
            childTermination != null -> childTermination
            isPartiallyExecuted -> StopOrderStatus.PARTIALLY_EXECUTED
            parent.parentOrderState == "REJECTED" || event == "ORDER_FAILED" -> StopOrderStatus.REJECTED
            parent.parentOrderState == "COMPLETED" || event in triggeredEvents -> StopOrderStatus.TRIGGERED
            parent.parentOrderState == "ACTIVE" && triggerVerified && waitingConfirmed -> StopOrderStatus.WAITING
            else -> StopOrderStatus.UNKNOWN
        }

    companion object {
        private val triggeredEvents = setOf("TRIGGER", "COMPLETE")
    }
}

internal data class StopChildVerification(
    val parent: ParentOrder,
    val parentEvent: ParentOrderEvent?,
    val childAcceptanceId: String?,
    val childEvent: ChildOrderEvent?
)

internal enum class StopOrderStatus {
    WAITING, TRIGGERED, PARTIALLY_EXECUTED, EXECUTED, CANCELED, EXPIRED, REJECTED, UNKNOWN
}
