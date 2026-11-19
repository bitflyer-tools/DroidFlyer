package com.unhappychoice.droidflyer.presentation

import android.content.Context
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent

internal fun Context.executionMessage(event: ChildOrderEvent): String =
    getString(
        R.string.order_executed,
        event.productCode,
        event.side ?: "",
        event.size ?: 0.0,
        event.price ?: 0L
    )
