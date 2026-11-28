package com.unhappychoice.droidflyer.extension

import com.winterbe.expekt.should
import org.junit.Test

class StringEventInstantTest {
    @Test
    fun ordersInstantsByActualTimeRatherThanFractionalDigitCount() {
        val noFraction = "2018-01-01T00:00:00Z".toEventInstantNanos()
        val longerFraction = "2018-01-01T00:00:00.1234567Z".toEventInstantNanos()

        (longerFraction > noFraction).should.equal(true)
    }

    @Test
    fun treatsEquivalentFractionsAsEqualRegardlessOfPadding() {
        "2018-01-01T00:00:00.5Z".toEventInstantNanos()
            .should.equal("2018-01-01T00:00:00.500Z".toEventInstantNanos())
    }

    @Test
    fun comparesAcrossDifferentSeconds() {
        val earlier = "2018-01-01T00:00:00.9999999Z".toEventInstantNanos()
        val later = "2018-01-01T00:00:01Z".toEventInstantNanos()

        (later > earlier).should.equal(true)
    }
}
