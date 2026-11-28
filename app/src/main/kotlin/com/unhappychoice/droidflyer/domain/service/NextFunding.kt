package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import java.math.BigDecimal
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class NextFunding(val ratio: BigDecimal, val settlementMillis: Long)

internal data class FundingStatus(val funding: NextFunding? = null, val stale: Boolean = false)

internal fun NextFunding.isExpired(nowMillis: Long): Boolean = nowMillis >= settlementMillis

internal fun FundingRate.toNextFunding(): NextFunding {
    val ratio = requireNotNull(ratio)
    val date = requireNotNull(settlementDate)
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val position = ParsePosition(0)
    val settlement = requireNotNull(parser.parse(date, position))
    require(position.index == date.length)
    return NextFunding(ratio, settlement.time)
}
