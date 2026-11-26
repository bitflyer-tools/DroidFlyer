package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OrderFillNotificationControllerTest {
    private val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
    private val states = mutableListOf<Boolean>()
    private val controller = OrderFillNotificationController(preference) { states.add(it) }

    @Test
    fun emitsCurrentStateThenTracksLinkAndUnlinkWithoutDuplicates() {
        val subscription = controller.start()
        try {
            assertEquals(listOf(false), states)

            preference.save(APICredentials("key", "secret"))
            assertEquals(listOf(false, true), states)

            preference.save(APICredentials("key", "secret"))
            assertEquals(listOf(false, true), states)

            preference.clear()
            assertEquals(listOf(false, true, false), states)
        } finally {
            subscription.dispose()
        }
    }
}
