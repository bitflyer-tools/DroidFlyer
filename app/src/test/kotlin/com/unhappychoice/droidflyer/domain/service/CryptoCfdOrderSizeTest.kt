package com.unhappychoice.droidflyer.domain.service

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoCfdOrderSizeTest {
    @Test
    fun quantityAtTheMinimumIsValid() {
        assertTrue(CryptoCfdOrderSize.meetsMinimum(CryptoCfdOrderSize.MINIMUM))
    }

    @Test
    fun quantityBelowTheMinimumIsRejected() {
        assertFalse(CryptoCfdOrderSize.meetsMinimum(CryptoCfdOrderSize.MINIMUM - 0.0001))
    }

    @Test
    fun zeroQuantityIsRejected() {
        assertFalse(CryptoCfdOrderSize.meetsMinimum(0.0))
    }

    @Test
    fun nonFiniteQuantityIsRejected() {
        assertFalse(CryptoCfdOrderSize.meetsMinimum(Double.NaN))
        assertFalse(CryptoCfdOrderSize.meetsMinimum(Double.POSITIVE_INFINITY))
    }

    @Test
    fun quantityAtTheMaximumIsValid() {
        assertTrue(CryptoCfdOrderSize.isValid(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM, CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM))
    }

    @Test
    fun quantityAboveTheMaximumIsRejected() {
        assertFalse(
            CryptoCfdOrderSize.isValid(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM + 0.001, CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM)
        )
    }

    @Test
    fun corporateMaximumAllowsSizesRejectedUnderTheIndividualMaximum() {
        val size = CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM + 1.0

        assertFalse(CryptoCfdOrderSize.isValid(size, CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM))
        assertTrue(CryptoCfdOrderSize.isValid(size, CryptoCfdOrderSize.CORPORATE_MAXIMUM))
    }

    @Test
    fun belowMinimumQuantityIsRejectedEvenUnderTheMaximum() {
        assertFalse(CryptoCfdOrderSize.isValid(CryptoCfdOrderSize.MINIMUM - 0.0001, CryptoCfdOrderSize.CORPORATE_MAXIMUM))
    }

    @Test
    fun bigDecimalQuantityAtTheMinimumIsValid() {
        assertTrue(CryptoCfdOrderSize.meetsMinimum(CryptoCfdOrderSize.MINIMUM.toBigDecimal()))
    }

    @Test
    fun bigDecimalQuantityBelowTheMinimumIsRejected() {
        assertFalse(CryptoCfdOrderSize.meetsMinimum(BigDecimal.valueOf(CryptoCfdOrderSize.MINIMUM - 0.0001)))
    }

    @Test
    fun bigDecimalQuantityAboveTheMaximumIsRejected() {
        val size = BigDecimal.valueOf(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM + 0.001)

        assertFalse(CryptoCfdOrderSize.isValid(size, CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM))
    }

    @Test
    fun bigDecimalQuantityWithinBoundsIsValid() {
        val size = CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM.toBigDecimal()

        assertTrue(CryptoCfdOrderSize.isValid(size, CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM))
    }
}
