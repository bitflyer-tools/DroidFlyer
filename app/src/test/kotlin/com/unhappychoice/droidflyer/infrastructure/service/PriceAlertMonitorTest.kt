package com.unhappychoice.droidflyer.infrastructure.service

import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceAlertMonitorTest {
    private val preference = PriceAlertPreference(RuntimeEnvironment.getApplication())
    private val executions = PublishSubject.create<List<Execution>>()
    private val triggered = mutableListOf<PriceAlert>()
    private val monitor = PriceAlertMonitor(preference, executions, triggered::add)

    @Test
    fun firesOnlyWhenThePriceCrossesTheTargetAndThenClearsTheAlert() {
        val alert = requireNotNull(preference.add(100, PriceAlertDirection.ABOVE))

        executions.onNext(listOf(execution(1, 90.0)))
        assertEquals(emptyList<PriceAlert>(), triggered)

        executions.onNext(listOf(execution(1, 110.0)))
        assertEquals(listOf(alert), triggered)
        assertEquals(emptyList<PriceAlert>(), preference.read())

        triggered.clear()
        executions.onNext(listOf(execution(1, 90.0)))
        executions.onNext(listOf(execution(1, 110.0)))
        assertEquals(emptyList<PriceAlert>(), triggered)
    }

    @Test
    fun firesForCrossingsWithinABatchInExecutionOrder() {
        val above = requireNotNull(preference.add(100, PriceAlertDirection.ABOVE))
        val below = requireNotNull(preference.add(100, PriceAlertDirection.BELOW))
        val changes = preference.alertsChanged.test()

        executions.onNext(listOf(execution(1, 90.0)))
        executions.onNext(
            listOf(
                execution(5, 95.0),
                execution(4, 90.0),
                execution(3, Double.NaN),
                execution(2, 110.0)
            )
        )

        assertEquals(listOf(above, below), triggered)
        changes.assertValues(listOf(below), emptyList())
    }

    @Test
    fun doesNotFireForTheFirstPriceReceivedAfterStartingToMonitor() {
        preference.add(100, PriceAlertDirection.BELOW)

        executions.onNext(listOf(execution(1, 90.0)))

        assertEquals(emptyList<PriceAlert>(), triggered)
    }

    @Test
    fun disposeStopsFurtherEvaluation() {
        preference.add(100, PriceAlertDirection.ABOVE)
        executions.onNext(listOf(execution(1, 90.0)))

        monitor.dispose()
        executions.onNext(listOf(execution(1, 110.0)))

        assertEquals(emptyList<PriceAlert>(), triggered)
    }

    private fun execution(id: Long, price: Double) =
        Execution(id, "BUY", price, 0.01, Date(id), "", "")
}
