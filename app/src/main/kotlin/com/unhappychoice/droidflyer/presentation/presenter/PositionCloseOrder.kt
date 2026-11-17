package com.unhappychoice.droidflyer.presentation.presenter

internal class PositionCloseOrder private constructor(positionSize: Double) {
    val side = if (positionSize > 0.0) "SELL" else "BUY"
    val size = Math.abs(positionSize)

    fun confirm(
        current: PositionCloseOrder?,
        submit: (PositionCloseOrder) -> Unit,
        reconfirm: (PositionCloseOrder) -> Unit
    ) {
        when {
            current == null -> return
            current.side == side && current.size == size -> submit(this)
            else -> reconfirm(current)
        }
    }

    companion object {
        fun from(size: Double): PositionCloseOrder? =
            if (size == 0.0) null else PositionCloseOrder(size)
    }
}
