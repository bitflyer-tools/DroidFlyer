package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.widget.TextView
import androidx.core.view.isVisible
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.MarketPrice
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.TestScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class CurrentStatusViewTest {
    private val scheduler = TestScheduler()
    private lateinit var service: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setSingleSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(scheduler) }

        val api = mock<APIClientV1>()
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        service = CurrentStatusService(api, Observable.empty(), Observable.empty(), Observable.empty(), null)
        service.currentPrice.value = MarketPrice(150.0)
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.dispose()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun offsettingPositionsShowBothSidesAndProfitUntilTheListIsEmpty() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val summary = view.findViewById<TextView>(R.id.position)
            val profit = view.findViewById<TextView>(R.id.profit)
            val spread = view.findViewById<TextView>(R.id.spread)
            scheduler.triggerActions()

            assertEquals("No position", summary.text.toString())
            assertFalse(profit.isVisible)

            val long = Position("FX_BTC_JPY", "BUY", 100L, 0.25, 0.0, 0.0, 0.0, 1L, 0.0)
            service.position.value = listOf(long, long.copy(side = "SELL", price = 200L))
            scheduler.triggerActions()

            assertEquals(
                "Long 0.25 / Short 0.25 ɃFX\nNet 0 ɃFX. Clear all positions is unavailable because the quantities offset.",
                summary.text.toString()
            )
            assertTrue(profit.isVisible)
            assertEquals("25", profit.text.toString().trim())
            assertFalse(spread.isVisible)

            service.position.value = emptyList()
            scheduler.triggerActions()

            assertEquals("No position", summary.text.toString())
            assertFalse(profit.isVisible)
        }
    }
}
