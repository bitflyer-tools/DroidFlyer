package com.unhappychoice.droidflyer.infrastructure.bitflyer.model

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class FundingRate(
    @SerializedName("current_funding_rate") val ratio: BigDecimal?,
    @SerializedName("next_funding_rate_settledate") val settlementDate: String?
)
