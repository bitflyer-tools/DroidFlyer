package com.unhappychoice.droidflyer.infrastructure.preference

import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceAlertPreferenceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<Any>(), PriceAlertPreference(context).read())
    }

    @Test
    fun addedAlertsArePersistedAndVisibleToAnotherInstance() {
        val store = PriceAlertPreference(context)
        val alert = requireNotNull(store.add(10_000_000, PriceAlertDirection.ABOVE))

        assertEquals(10_000_000L, alert.targetPrice)
        assertEquals(PriceAlertDirection.ABOVE, alert.direction)
        assertEquals(listOf(alert), PriceAlertPreference(context).read())
    }

    @Test
    fun eachAddedAlertGetsAUniqueId() {
        val store = PriceAlertPreference(context)
        val first = requireNotNull(store.add(10_000_000, PriceAlertDirection.ABOVE))
        val second = requireNotNull(store.add(9_000_000, PriceAlertDirection.BELOW))

        assertTrue(first.id != second.id)
        assertEquals(listOf(first, second), store.read())
    }

    @Test
    fun removingAnAlertDropsOnlyThatOne() {
        val store = PriceAlertPreference(context)
        val first = requireNotNull(store.add(10_000_000, PriceAlertDirection.ABOVE))
        val second = requireNotNull(store.add(9_000_000, PriceAlertDirection.BELOW))

        store.remove(first.id)

        assertEquals(listOf(second), store.read())
    }

    @Test
    fun addingBeyondTheLimitIsRejected() {
        val store = PriceAlertPreference(context)
        repeat(PriceAlertPreference.MAX_ALERTS) {
            assertNotNull(store.add((it + 1).toLong(), PriceAlertDirection.ABOVE))
        }

        assertNull(store.add(999, PriceAlertDirection.ABOVE))
        assertEquals(PriceAlertPreference.MAX_ALERTS, store.read().size)
    }

    @Test
    fun changesAreEmittedToSubscribers() {
        val store = PriceAlertPreference(context)
        val changes = store.alertsChanged.test()

        val alert = requireNotNull(store.add(10_000_000, PriceAlertDirection.ABOVE))
        store.remove(alert.id)

        changes.assertValues(listOf(alert), emptyList())
    }
}
