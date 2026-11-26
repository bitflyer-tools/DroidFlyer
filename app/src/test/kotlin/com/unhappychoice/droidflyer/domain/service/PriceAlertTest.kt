package com.unhappychoice.droidflyer.domain.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceAlertTest {
    private val above = PriceAlert(1, 100, PriceAlertDirection.ABOVE)
    private val below = PriceAlert(2, 100, PriceAlertDirection.BELOW)

    @Test
    fun hasNoPreviousPriceTriggersNothing() {
        assertEquals(emptyList<PriceAlert>(), triggeredAlerts(null, 150.0, listOf(above, below)))
    }

    @Test
    fun aboveAlertTriggersWhenPriceCrossesUpThroughTheTarget() {
        assertEquals(listOf(above), triggeredAlerts(90.0, 110.0, listOf(above)))
    }

    @Test
    fun aboveAlertTriggersWhenPriceLandsExactlyOnTheTarget() {
        assertEquals(listOf(above), triggeredAlerts(90.0, 100.0, listOf(above)))
    }

    @Test
    fun aboveAlertDoesNotTriggerWhenThePriceWasAlreadyAboveTheTarget() {
        assertEquals(emptyList<PriceAlert>(), triggeredAlerts(110.0, 120.0, listOf(above)))
    }

    @Test
    fun aboveAlertDoesNotTriggerWhenThePriceStaysBelowTheTarget() {
        assertEquals(emptyList<PriceAlert>(), triggeredAlerts(80.0, 90.0, listOf(above)))
    }

    @Test
    fun belowAlertTriggersWhenPriceCrossesDownThroughTheTarget() {
        assertEquals(listOf(below), triggeredAlerts(110.0, 90.0, listOf(below)))
    }

    @Test
    fun belowAlertDoesNotTriggerWhenThePriceWasAlreadyBelowTheTarget() {
        assertEquals(emptyList<PriceAlert>(), triggeredAlerts(90.0, 80.0, listOf(below)))
    }

    @Test
    fun onlyAlertsThatCrossTheirOwnTargetAreReturned() {
        assertEquals(listOf(above), triggeredAlerts(90.0, 110.0, listOf(above, below)))
    }
}
