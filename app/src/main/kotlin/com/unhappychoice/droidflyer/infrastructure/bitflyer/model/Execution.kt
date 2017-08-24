package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import java.util.*

data class Execution(
    val id: Long,
    val side: String,
    val price: Double,
    val size: Double,
    val execDate: Date,
    val buyChildOrderAcceptanceId: String,
    val sellChildOrderAcceptanceId: String
)