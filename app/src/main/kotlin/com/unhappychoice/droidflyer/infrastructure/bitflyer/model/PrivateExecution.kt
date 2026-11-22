package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class PrivateExecution(
    val id: Long,
    val side: String,
    val price: BigDecimal,
    val size: BigDecimal,
    @SerializedName("exec_date") val execDate: String
)
