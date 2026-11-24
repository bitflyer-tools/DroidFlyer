package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NextFundingTest {
    @Test
    fun parsesTheSettlementDateAsUtcMillis() {
        val rate = FundingRate(BigDecimal("-0.00375"), "2024-04-15T13:00:00")

        val funding = rate.toNextFunding()

        assertEquals(BigDecimal("-0.00375"), funding.ratio)
        assertEquals(1713186000000L, funding.settlementMillis)
    }

    @Test
    fun missingRatioIsRejected() {
        val rate = FundingRate(null, "2024-04-15T13:00:00")

        assertThrows(IllegalArgumentException::class.java) { rate.toNextFunding() }
    }

    @Test
    fun missingSettlementDateIsRejected() {
        val rate = FundingRate(BigDecimal.ZERO, null)

        assertThrows(IllegalArgumentException::class.java) { rate.toNextFunding() }
    }

    @Test
    fun unparsableSettlementDateIsRejected() {
        val rate = FundingRate(BigDecimal.ZERO, "not-a-date")

        assertThrows(IllegalArgumentException::class.java) { rate.toNextFunding() }
    }

    @Test
    fun settlementDateWithTrailingCharactersIsRejected() {
        val rate = FundingRate(BigDecimal.ZERO, "2024-04-15T13:00:00.1234567Z")

        assertThrows(IllegalArgumentException::class.java) { rate.toNextFunding() }
    }
}
