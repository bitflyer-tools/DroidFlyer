package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.Collateral
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.MarketPrice
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ReadStatus
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
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
    private val publicFeedDisconnects = PublishSubject.create<Unit>()
    private lateinit var service: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setSingleSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(scheduler) }

        val api = mock<APIClientV1>()
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getFundingRate("FX_BTC_JPY")).thenReturn(Observable.empty())
        service = CurrentStatusService(
            api, Observable.empty(), Observable.empty(), Observable.empty(), null,
            publicFeedDisconnects = publicFeedDisconnects
        )
        service.currentPrice.value = MarketPrice(150.0)
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.dispose()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun queuedValuationsRenderEachPositionWithItsOwnAmounts() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            service.privateState.collateral.value = Collateral(100.0)
            scheduler.triggerActions()

            val summary = view.findViewById<TextView>(R.id.position)
            val profit = view.findViewById<TextView>(R.id.profit)
            val spread = view.findViewById<TextView>(R.id.spread)
            val balance = view.findViewById<TextView>(R.id.balance)
            val renderedHeaders = mutableListOf<List<String>>()
            balance.doAfterTextChanged {
                renderedHeaders.add(listOf(summary, profit, spread, balance).map { it.text.toString().trim() })
            }

            val long = Position("FX_BTC_JPY", "BUY", 100L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0)
            service.position.value = listOf(long)
            service.position.value = listOf(long.copy(side = "SELL", price = 130L))
            assertTrue(renderedHeaders.isEmpty())
            scheduler.triggerActions()

            assertEquals(
                listOf(
                    listOf("100 JPY / 0.5 ɃFX", "25", "50", "125 JPY"),
                    listOf("130 JPY / -0.5 ɃFX", "-10", "-20", "90 JPY")
                ),
                renderedHeaders
            )
        }
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
            assertEquals("Balance unavailable", view.findViewById<TextView>(R.id.balance).text.toString())
            assertFalse(spread.isVisible)

            service.privateState.collateral.value = Collateral(100.0)
            publicFeedDisconnects.onNext(Unit)
            scheduler.triggerActions()

            assertEquals("—", view.findViewById<TextView>(R.id.currentPrice).text.toString())
            assertTrue(profit.isVisible)
            assertEquals("—", profit.text.toString().trim())
            assertFalse(spread.isVisible)
            assertEquals("Collateral: 100 JPY", view.findViewById<TextView>(R.id.balance).text.toString())

            service.position.value = emptyList()
            scheduler.triggerActions()

            assertEquals("No position", summary.text.toString())
            assertFalse(profit.isVisible)
        }
    }

    @Test
    fun failedPositionRefreshKeepsRepricedValuationsStaleUntilTheNextSuccessfulRead() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val summary = view.findViewById<TextView>(R.id.position)
            val profit = view.findViewById<TextView>(R.id.profit)
            val spread = view.findViewById<TextView>(R.id.spread)
            val balance = view.findViewById<TextView>(R.id.balance)

            val long = Position("FX_BTC_JPY", "BUY", 100L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0)
            val client = mock<APIClientV1>()
            whenever(client.getPositions()).thenReturn(
                Observable.just(listOf(long)),
                Observable.error(IllegalStateException("Position read failed")),
                Observable.just(listOf(long.copy(price = 160L)))
            )
            whenever(client.getCollateral()).thenReturn(Observable.just(mapOf("collateral" to 100.0)))
            service.privateState.switchAccount(client)

            service.privateState.refreshPositionsAndCollateral().test()
            scheduler.triggerActions()
            assertEquals("100 JPY / 0.5 ɃFX", summary.text.toString())
            assertEquals("25", profit.text.toString().trim())
            assertEquals("50", spread.text.toString().trim())

            service.privateState.refreshPositionsAndCollateral().test()
            scheduler.triggerActions()
            assertEquals("100 JPY / 0.5 ɃFX (stale)", summary.text.toString())
            assertEquals("25 (stale)", profit.text.toString().trim())
            assertEquals("50 (stale)", spread.text.toString().trim())
            assertEquals("125 JPY (stale)", balance.text.toString())

            service.currentPrice.value = MarketPrice(180.0)
            scheduler.advanceTimeBy(100, TimeUnit.MILLISECONDS)
            assertEquals("40 (stale)", profit.text.toString().trim())
            assertEquals("80 (stale)", spread.text.toString().trim())
            assertEquals("140 JPY (stale)", balance.text.toString())

            service.privateState.refreshPositionsAndCollateral().test()
            scheduler.triggerActions()
            assertEquals("10", profit.text.toString().trim())
            assertEquals("20", spread.text.toString().trim())
            assertEquals("110 JPY", balance.text.toString())
        }
    }

    @Test
    fun firstFailedPositionReadHidesTheValuedBalanceUntilPositionsAreKnown() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val balance = view.findViewById<TextView>(R.id.balance)
            val client = mock<APIClientV1>()
            whenever(client.getPositions()).thenReturn(
                Observable.error(IllegalStateException("Position read failed")),
                Observable.just(emptyList())
            )
            whenever(client.getCollateral()).thenReturn(Observable.just(mapOf("collateral" to 100.0)))
            service.privateState.switchAccount(client)

            service.privateState.refreshPositionsAndCollateral().test()
            scheduler.triggerActions()
            assertEquals("150 JPY", view.findViewById<TextView>(R.id.currentPrice).text.toString())
            assertEquals("Position unavailable", view.findViewById<TextView>(R.id.position).text.toString())
            assertEquals("Balance unavailable", balance.text.toString())

            service.privateState.refreshPositionsAndCollateral().test()
            scheduler.triggerActions()
            assertEquals("100 JPY", balance.text.toString())
        }
    }

    @Test
    fun unavailablePositionHidesTheCachedPositionAndItsValuations() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val summary = view.findViewById<TextView>(R.id.position)

            service.position.value = listOf(Position("FX_BTC_JPY", "BUY", 100L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0))
            service.privateState.collateral.value = Collateral(100.0)
            service.privateState.positionStatus.value = ReadStatus.UNAVAILABLE
            scheduler.triggerActions()

            assertEquals("Position unavailable", summary.text.toString())
            assertFalse(view.findViewById<TextView>(R.id.profit).isVisible)
            assertFalse(view.findViewById<TextView>(R.id.spread).isVisible)
            assertEquals("Balance unavailable", view.findViewById<TextView>(R.id.balance).text.toString())
        }
    }

    @Test
    fun failedCollateralRefreshAfterASuccessfulReadMarksTheBalanceStale() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val balance = view.findViewById<TextView>(R.id.balance)

            service.privateState.collateral.value = Collateral(100.0)
            service.privateState.collateralStatus.value = ReadStatus.AVAILABLE
            scheduler.triggerActions()
            assertEquals("100 JPY", balance.text.toString())

            service.privateState.collateralStatus.value = ReadStatus.STALE
            scheduler.triggerActions()
            assertEquals("100 JPY (stale)", balance.text.toString())
        }
    }

    @Test
    fun unavailableCollateralHidesTheCachedBalance() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            val balance = view.findViewById<TextView>(R.id.balance)

            service.privateState.collateral.value = Collateral(100.0)
            service.privateState.collateralStatus.value = ReadStatus.UNAVAILABLE
            scheduler.triggerActions()

            assertEquals("Balance unavailable", balance.text.toString())
        }
    }

    @Test
    @Config(qualifiers = "ja")
    fun balanceAndCollateralTextsAreLocalizedToJapanese() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val view = CurrentStatusView(activity, null)
            view.inject(DI { bindInstance<CurrentStatusService> { service } })
            activity.setContentView(view)
            scheduler.triggerActions()

            assertEquals("残高を取得できません", view.findViewById<TextView>(R.id.balance).text.toString())

            service.privateState.collateral.value = Collateral(100.0)
            publicFeedDisconnects.onNext(Unit)
            scheduler.triggerActions()

            assertEquals("預入金: 100 JPY", view.findViewById<TextView>(R.id.balance).text.toString())
        }
    }
}
