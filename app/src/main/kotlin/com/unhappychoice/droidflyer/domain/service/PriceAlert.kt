package com.unhappychoice.droidflyer.domain.service

internal data class PriceAlert(val id: Long, val targetPrice: Long, val direction: PriceAlertDirection)

internal enum class PriceAlertDirection { ABOVE, BELOW }

// Alerts are one-shot, so only a crossing (not an already-satisfied level) should trigger them.
internal fun triggeredAlerts(previousPrice: Double?, currentPrice: Double, alerts: List<PriceAlert>): List<PriceAlert> {
    if (previousPrice == null) return emptyList()
    return alerts.filter { it.direction.isCrossedBy(previousPrice, currentPrice, it.targetPrice) }
}

private fun PriceAlertDirection.isCrossedBy(previous: Double, current: Double, target: Long): Boolean = when (this) {
    PriceAlertDirection.ABOVE -> previous < target && current >= target
    PriceAlertDirection.BELOW -> previous > target && current <= target
}
