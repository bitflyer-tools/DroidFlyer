package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertsPresenterTest {
    private val preference = PriceAlertPreference(RuntimeEnvironment.getApplication())
    private var monitoringStarts = 0
    private val presenter = AlertsPresenter(preference) { monitoringStarts++ }

    @Test
    fun addingAnAlertPersistsItAndStartsMonitoring() {
        val added = presenter.addAlert(100, PriceAlertDirection.ABOVE)

        assertTrue(added)
        assertEquals(1, monitoringStarts)
        assertEquals(1, preference.read().size)
    }

    @Test
    fun reachingTheLimitDoesNotStartMonitoringAgain() {
        repeat(PriceAlertPreference.MAX_ALERTS) { presenter.addAlert((it + 1).toLong(), PriceAlertDirection.ABOVE) }
        monitoringStarts = 0

        val added = presenter.addAlert(999, PriceAlertDirection.ABOVE)

        assertFalse(added)
        assertEquals(0, monitoringStarts)
    }

    @Test
    fun removingAnAlertUpdatesStoredAlerts() {
        presenter.addAlert(100, PriceAlertDirection.ABOVE)
        val alert = preference.read().single()

        presenter.removeAlert(alert.id)

        assertEquals(emptyList<Any>(), preference.read())
    }

    @Test
    fun alertsObservableEmitsStoredAlertsFirst() {
        presenter.addAlert(100, PriceAlertDirection.ABOVE)
        val observed = presenter.alerts.test()

        observed.assertValueCount(1)
        assertEquals(preference.read(), observed.values().single())
    }
}
