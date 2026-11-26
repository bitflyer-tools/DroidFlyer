package com.unhappychoice.droidflyer.infrastructure.service

import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceAlertMonitorTest {
    private val preference = PriceAlertPreference(RuntimeEnvironment.getApplication())
    private val prices = PublishSubject.create<Double>()
    private val triggered = mutableListOf<PriceAlert>()
    private val monitor = PriceAlertMonitor(preference, prices, triggered::add)

    @Test
    fun firesOnlyWhenThePriceCrossesTheTargetAndThenClearsTheAlert() {
        val alert = requireNotNull(preference.add(100, PriceAlertDirection.ABOVE))

        prices.onNext(90.0)
        assertEquals(emptyList<PriceAlert>(), triggered)

        prices.onNext(110.0)
        assertEquals(listOf(alert), triggered)
        assertEquals(emptyList<PriceAlert>(), preference.read())

        triggered.clear()
        prices.onNext(90.0)
        prices.onNext(110.0)
        assertEquals(emptyList<PriceAlert>(), triggered)
    }

    @Test
    fun doesNotFireForTheFirstPriceReceivedAfterStartingToMonitor() {
        preference.add(100, PriceAlertDirection.BELOW)

        prices.onNext(90.0)

        assertEquals(emptyList<PriceAlert>(), triggered)
    }

    @Test
    fun disposeStopsFurtherEvaluation() {
        preference.add(100, PriceAlertDirection.ABOVE)
        prices.onNext(90.0)

        monitor.dispose()
        prices.onNext(110.0)

        assertEquals(emptyList<PriceAlert>(), triggered)
    }
}
