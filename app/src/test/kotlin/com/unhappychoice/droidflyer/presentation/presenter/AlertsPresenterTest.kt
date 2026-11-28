package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.di.applicationModule
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.instance
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

    @Test
    fun applicationStoreAndPresenterObserveEachOthersRemovals() {
        val application = RuntimeEnvironment.getApplication() as DroidFlyerApplication
        val serviceStore = application.priceAlertPreference
        val di = DI { import(applicationModule(application)) }
        val presenterStore: PriceAlertPreference by di.instance()
        val presenter = AlertsPresenter(presenterStore) {}
        val first = requireNotNull(serviceStore.add(100, PriceAlertDirection.ABOVE))
        val second = requireNotNull(serviceStore.add(50, PriceAlertDirection.BELOW))
        val visibleAlerts = presenter.alerts.test()
        val serviceChanges = serviceStore.alertsChanged.test()

        serviceStore.remove(first.id)
        presenter.removeAlert(second.id)

        visibleAlerts.assertValues(listOf(first, second), listOf(second), emptyList())
        serviceChanges.assertValues(listOf(second), emptyList())
    }
}
