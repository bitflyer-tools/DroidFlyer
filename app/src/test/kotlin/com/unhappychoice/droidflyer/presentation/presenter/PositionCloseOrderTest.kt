package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.PositionCloseOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionCloseOrderTest {
    @Test
    fun submitsConfirmedSideAndSizeWhenUnchanged() {
        val (submitted, reconfirmed) = confirm(0.10, 0.10)

        assertEquals("SELL", submitted?.side)
        assertEquals(0.10, requireNotNull(submitted).size, 0.0)
        assertNull(reconfirmed)
    }

    @Test
    fun requiresFreshConfirmationAfterSizeChange() {
        val (submitted, reconfirmed) = confirm(0.10, 0.20)

        assertNull(submitted)
        assertEquals("SELL", reconfirmed?.side)
        assertEquals(0.20, requireNotNull(reconfirmed).size, 0.0)
    }

    @Test
    fun requiresFreshConfirmationAfterSideReversal() {
        val (submitted, reconfirmed) = confirm(0.10, -0.10)

        assertNull(submitted)
        assertEquals("BUY", reconfirmed?.side)
        assertEquals(0.10, requireNotNull(reconfirmed).size, 0.0)
    }

    @Test
    fun dismissesConfirmationWhenPositionIsFlat() {
        val (submitted, reconfirmed) = confirm(0.10, 0.0)

        assertNull(submitted)
        assertNull(reconfirmed)
    }

    @Test
    fun rejectsPositionsBelowTheMinimumOrderSize() {
        assertNull(PositionCloseOrder.from(0.00099999))
        assertNull(PositionCloseOrder.from(-0.00099999))
    }

    @Test
    fun acceptsAPositionAtExactlyTheMinimumOrderSize() {
        assertEquals(0.001, requireNotNull(PositionCloseOrder.from(0.001)).size, 0.0)
        assertEquals(0.001, requireNotNull(PositionCloseOrder.from(-0.001)).size, 0.0)
    }

    private fun confirm(
        confirmedSize: Double,
        currentSize: Double
    ): Pair<PositionCloseOrder?, PositionCloseOrder?> {
        var submitted: PositionCloseOrder? = null
        var reconfirmed: PositionCloseOrder? = null
        requireNotNull(PositionCloseOrder.from(confirmedSize)).confirm(
            PositionCloseOrder.from(currentSize),
            submit = { submitted = it },
            reconfirm = { reconfirmed = it }
        )
        return submitted to reconfirmed
    }
}
