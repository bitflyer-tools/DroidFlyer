package com.unhappychoice.droidflyer.domain.service

internal class PositionCloseOrder private constructor(positionSize: Double) {
    val side = if (positionSize > 0.0) "SELL" else "BUY"
    val size = Math.abs(positionSize)

    fun <T> confirm(
        current: PositionCloseOrder?,
        submit: (PositionCloseOrder) -> T,
        reconfirm: (PositionCloseOrder) -> Unit
    ): T? {
        if (current == null) return null
        if (current.side == side && current.size == size) return submit(this)
        reconfirm(current)
        return null
    }

    companion object {
        fun from(size: Double): PositionCloseOrder? =
            if (size == 0.0) null else PositionCloseOrder(size)
    }
}
