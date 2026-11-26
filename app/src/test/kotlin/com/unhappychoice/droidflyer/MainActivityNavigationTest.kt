package com.unhappychoice.droidflyer

import android.Manifest
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.widget.TextView
import androidx.core.view.children
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.databinding.OrderViewBinding
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentialCipher
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testCredentialKey
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderSide
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import com.unhappychoice.droidflyer.presentation.view.ChartView
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import com.unhappychoice.droidflyer.presentation.view.MarketOrderView
import com.unhappychoice.droidflyer.presentation.view.OrderView
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import flow.Flow
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import io.socket.client.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.direct
import org.kodein.di.instance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [MainActivityNavigationTest.DisconnectedSocket::class],
    instrumentedPackages = ["io.socket.client"]
)
class MainActivityNavigationTest {
    private val background = TestScheduler()

    @Before
    fun pauseBackgroundRequests() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        DisconnectedSocket.connections.clear()
        RxJavaPlugins.setIoSchedulerHandler { background }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
    }

    @Test
    fun startupContinuesWhenLegacyCredentialsCannotBeEncrypted() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("api_token", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("key", "legacy-key").putString("secret", "legacy-secret").commit())

        // Robolectric does not provide the AndroidKeyStore provider used by the activity.
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertDestination(controller.get(), R.id.chartIcon, ChartView::class.java, 1)
            assertEquals("legacy-key", preferences.getString("key", null))
            assertEquals("legacy-secret", preferences.getString("secret", null))
        }

        val credentials = testAPITokenPreference(application).read()
        assertEquals("legacy-key", credentials.key)
        assertEquals("legacy-secret", credentials.secret)
        assertFalse(preferences.contains("key"))
        assertFalse(preferences.contains("secret"))
    }

    @Test
    fun backChecksTheDestinationAndDoesNotAddHistory() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertDestination(activity, R.id.chartIcon, ChartView::class.java, 1)
            activity.tapTab(R.id.orderIcon)
            assertDestination(activity, R.id.orderIcon, OrderView::class.java, 2)
            activity.tapTab(R.id.settingsIcon)
            assertDestination(activity, R.id.settingsIcon, SettingsView::class.java, 3)

            activity.onBackPressed()
            assertDestination(activity, R.id.orderIcon, OrderView::class.java, 2)
            activity.onBackPressed()
            assertDestination(activity, R.id.chartIcon, ChartView::class.java, 1)
        }
    }

    @Test
    @Config(
        shadows = [OfflineApi::class],
        instrumentedPackages = ["com.unhappychoice.droidflyer.infrastructure.bitflyer.http"]
    )
    fun orderPollsOnlyWhileStartedAndRefreshesWhenRestarted() {
        val polling = TestScheduler()
        RxJavaPlugins.setComputationSchedulerHandler { polling }
        val requests = mutableListOf<String>()
        val privateClient = recordingStatusClient(requests)
        val statusRead = listOf("positions", "collateral", "orders")

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.module.direct.instance<CurrentStatusService>().switchAccount(privateClient)
            drainStatusRequests()
            requests.clear()
            activity.tapTab(R.id.orderIcon)
            polling.triggerActions()
            drainStatusRequests()
            assertEquals(statusRead, requests)
            repeat(3) {
                val order = activity.binding.containerView.getChildAt(0)
                requests.clear()
                controller.pause().stop()
                assertTrue(order.isAttachedToWindow)
                assertSame(order, activity.binding.containerView.getChildAt(0))
                polling.advanceTimeBy(13, TimeUnit.SECONDS)
                drainStatusRequests()
                assertTrue(requests.isEmpty())

                controller.restart().resume()
                assertDestination(activity, R.id.orderIcon, OrderView::class.java, 2)
                polling.triggerActions()
                drainStatusRequests()
                assertEquals(statusRead, requests)
                requests.clear()

                polling.advanceTimeBy(5, TimeUnit.SECONDS)
                drainStatusRequests()
                assertTrue(requests.isEmpty())
                polling.advanceTimeBy(1, TimeUnit.SECONDS)
                drainStatusRequests()
                assertEquals(statusRead, requests)
            }

            requests.clear()
            activity.tapTab(R.id.chartIcon)
            polling.advanceTimeBy(12, TimeUnit.SECONDS)
            drainStatusRequests()
            assertTrue(requests.isEmpty())

            activity.tapTab(R.id.orderIcon)
            polling.triggerActions()
            drainStatusRequests()
            assertEquals(statusRead, requests)
        }
    }

    @Test
    fun settingsBlocksScreenCaptureUntilNavigatingBack() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertFalse(activity.isScreenCaptureBlocked())

            activity.tapTab(R.id.settingsIcon)
            assertTrue(activity.isScreenCaptureBlocked())

            activity.onBackPressed()
            assertFalse(activity.isScreenCaptureBlocked())
        }
    }

    @Test
    fun recreationBlocksCaptureOfRestoredSettingsUntilSwitchingTabs() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            controller.get().tapTab(R.id.settingsIcon)
            controller.recreate()

            val activity = controller.get()
            assertDestination(activity, R.id.settingsIcon, SettingsView::class.java, 2)
            assertTrue(activity.isScreenCaptureBlocked())

            activity.tapTab(R.id.orderIcon)
            assertFalse(activity.isScreenCaptureBlocked())
        }
    }

    @Test
    fun reattachingOrderKeepsOnePagerAndOneListenerInEachDirection() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            val parent = activity.binding.containerView
            val order = OrderViewBinding.bind(parent.getChildAt(0))
            val adapter = order.viewPager.adapter

            repeat(3) {
                parent.removeView(order.root)
                parent.addView(order.root)

                assertEquals(adapter, order.viewPager.adapter)
                assertEquals(3, order.tabLayout.tabCount)
                assertEquals(3, order.viewPager.adapter?.count)
                assertEquals(1, ReflectionHelpers.getField<List<TabLayout.OnTabSelectedListener>>(
                    order.tabLayout, "selectedListeners"
                ).size)
                assertEquals(1, ReflectionHelpers.getField<List<ViewPager.OnPageChangeListener>>(
                    order.viewPager, "mOnPageChangeListeners"
                ).size)
                order.tabLayout.getTabAt(1)?.select()
                assertEquals(1, order.viewPager.currentItem)
                order.viewPager.setCurrentItem(0, false)
                assertEquals(0, order.tabLayout.selectedTabPosition)
            }
        }
    }

    @Test
    fun recreationChecksTheRestoredDestinationWithStaleWidgetState() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            activity.binding.bottomNavigation.apply {
                isSaveFromParentEnabled = true
                menu.findItem(R.id.settingsIcon).isChecked = true
            }

            controller.recreate()

            assertDestination(controller.get(), R.id.orderIcon, OrderView::class.java, 2)
        }
    }

    @Test
    fun recreationReplacesTheRealtimeClientAndDetachesItsSocketListeners() {
        RuntimeEnvironment.getApplication().getSharedPreferences("api_token", Context.MODE_PRIVATE).edit()
            .putString("key", "legacy-key")
            .putString("secret", "legacy-secret")
            .apply()

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val oldClient = controller.get().module.direct.instance<RealtimeClient>()
            background.triggerActions()
            val (oldPublic, oldPrivate) = DisconnectedSocket.connections
            assertTrue(oldPublic.hasListeners("lightning_board_FX_BTC_JPY"))
            assertTrue(oldPrivate.hasListeners("child_order_events"))

            controller.recreate()

            val newClient = controller.get().module.direct.instance<RealtimeClient>()
            background.triggerActions()
            assertNotSame(oldClient, newClient)
            assertFalse(oldPublic.hasListeners("lightning_board_FX_BTC_JPY"))
            assertFalse(oldPrivate.hasListeners("child_order_events"))
            val (newPublic, newPrivate) = DisconnectedSocket.connections.drop(2)
            assertTrue(newPublic.hasListeners("lightning_board_FX_BTC_JPY"))
            assertTrue(newPrivate.hasListeners("child_order_events"))
        }
    }

    @Test
    fun delayedLimitOrderAcceptanceShowsOnChart() {
        assertLimitOrderFeedbackAfterNavigation(R.id.chartIcon, { getString(R.string.ordered_buy, 0.001, 100L) }) {
            onNext(ChildOrderAcceptance("test"))
            onComplete()
        }
    }

    @Test
    fun delayedLimitOrderRejectionShowsOnSettings() {
        assertLimitOrderFeedbackAfterNavigation(R.id.settingsIcon, { getString(R.string.limit_order_failed) }) {
            onError(HttpException(Response.error<ChildOrderAcceptance>(400, "Order rejected".toResponseBody())))
        }
    }

    @Test
    fun delayedMarketOrderAcceptanceShowsOnChart() {
        assertMarketOrderFeedbackAfterNavigation(R.id.chartIcon, { getString(R.string.market_order_accepted) }) {
            onNext(ChildOrderAcceptance("test"))
            onComplete()
        }
    }

    @Test
    fun delayedMarketOrderRejectionShowsOnSettings() {
        assertMarketOrderFeedbackAfterNavigation(R.id.settingsIcon, { getString(R.string.market_order_failed) }) {
            onError(HttpException(Response.error<ChildOrderAcceptance>(400, "Order rejected".toResponseBody())))
        }
    }

    @Test
    fun delayedPositionCloseAcceptanceShowsOnSettings() {
        val apiClient = mock<APIClientV1>()
        val positions = listOf(Position("FX_BTC_JPY", "BUY", 1000000L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0))
        val pending = PublishSubject.create<ChildOrderAcceptance>()
        val filledOrder = ChildOrder(
            1, "child", "FX_BTC_JPY", "SELL", "MARKET", 0, 0, 0.5,
            "COMPLETED", "JRF-close", 0.0, 0.0, 0.5, 0.0
        )
        whenever(apiClient.getPositions("FX_BTC_JPY")).thenReturn(Observable.never())
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending)
        whenever(apiClient.getChildOrders("FX_BTC_JPY", null, "JRF-close")).thenReturn(Observable.just(listOf(filledOrder)))
        whenever(apiClient.getCollateral()).thenReturn(Observable.never())
        whenever(apiClient.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.never())
        whenever(apiClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.never())

        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            val order = OrderViewBinding.bind(activity.binding.containerView.getChildAt(0))
            val pager = order.viewPager
            val page = pager.children.filterIsInstance<MarketOrderView>().firstOrNull()
                ?: pager.adapter!!.instantiateItem(pager, 0) as MarketOrderView

            val status = CurrentStatusService(apiClient, Observable.never(), Observable.never(), Observable.never(), apiClient)
            background.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            status.position.value = positions
            val presenter = MarketOrderPresenter(
                status, activity.module.direct.instance(), activity.resources, activity::showSnackbar
            )
            presenter.takeView(page)

            // The close flow reads getPositions twice: the confirmed open size, then the freshly emptied position.
            whenever(apiClient.getPositions("FX_BTC_JPY")).thenReturn(Observable.just(positions), Observable.just(emptyList()))
            val confirmed = requireNotNull(presenter.positionCloseOrder())
            presenter.clearPosition(confirmed) { error("Unexpected reconfirmation") }
            background.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(presenter.isLoading.value)
            assertTrue(page.isAttachedToWindow)
            activity.tapTab(R.id.settingsIcon)
            assertFalse(page.isAttachedToWindow)
            presenter.dropView(page)
            assertTrue(presenter.isLoading.value)

            pending.onNext(ChildOrderAcceptance("JRF-close"))
            pending.onComplete()
            background.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(presenter.isLoading.value)
            val message = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertEquals(activity.getString(R.string.cleared_all_positions), message?.text?.toString())
            verify(apiClient).sendChildOrder(
                SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5, timeInForce = "IOC")
            )
        }
    }

    @Test
    fun recreationFinishesWhileCredentialSaveIsInProgress() {
        val ioStarted = CountDownLatch(1)
        val resumeIo = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val credentials = APICredentials("replacement-key", "replacement-secret")
        val cipher = APICredentialCipher { create ->
            if (create) {
                ioStarted.countDown()
                check(resumeIo.await(10, TimeUnit.SECONDS)) { "Activity recreation blocked credential save" }
            }
            testCredentialKey
        }
        val preference = APITokenPreference(RuntimeEnvironment.getApplication(), cipher)

        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val oldClient = controller.get().module.direct.instance<RealtimeClient>()
                val oldSocket = DisconnectedSocket.connections.single()
                val save = executor.submit {
                    oldClient.updateAccount { preference.save(credentials) }
                }

                try {
                    assertTrue(ioStarted.await(10, TimeUnit.SECONDS))
                    controller.recreate()
                    assertFalse(oldSocket.hasListeners("lightning_board_FX_BTC_JPY"))
                    assertNotSame(oldClient, controller.get().module.direct.instance<RealtimeClient>())
                } finally {
                    resumeIo.countDown()
                }
                save.get(10, TimeUnit.SECONDS)
                val saved = preference.read()
                assertEquals(credentials.key to credentials.secret, saved.key to saved.secret)
                assertEquals(2, DisconnectedSocket.connections.size)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertLimitOrderFeedbackAfterNavigation(
        destination: Int,
        expectedMessage: MainActivity.() -> String,
        completeOrder: PublishSubject<ChildOrderAcceptance>.() -> Unit
    ) {
        val apiClient = mock<APIClientV1>()
        val pending = PublishSubject.create<ChildOrderAcceptance>()
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            val order = OrderViewBinding.bind(activity.binding.containerView.getChildAt(0))
            val pager = order.viewPager
            val page = pager.children.filterIsInstance<LimitOrderView>().firstOrNull()
                ?: pager.adapter!!.instantiateItem(pager, 1) as LimitOrderView

            val orderScheduler = TestScheduler()
            val state = PrivateTradingState(apiClient, requestScheduler = orderScheduler)
            val presenter = LimitOrderPresenter(
                state, activity.module.direct.instance(), activity.resources, activity::showSnackbar
            )
            presenter.takeView(page)
            presenter.amount.value = 0.001
            presenter.submitOrder(requireNotNull(presenter.prepareOrder(LimitOrderSide.BUY, 100)))
            orderScheduler.triggerActions()

            assertTrue(presenter.isLoading.value)
            assertTrue(page.isAttachedToWindow)
            activity.tapTab(destination)
            assertFalse(page.isAttachedToWindow)
            presenter.dropView(page)
            assertTrue(presenter.isLoading.value)

            pending.completeOrder()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(presenter.isLoading.value)
            val message = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertEquals(activity.expectedMessage(), message?.text?.toString())
            verify(apiClient).sendChildOrder(SendChildOrderRequest("FX_BTC_JPY", "LIMIT", "BUY", 100, 0.001))
            verifyNoMoreInteractions(apiClient)
        }
    }

    private fun assertMarketOrderFeedbackAfterNavigation(
        destination: Int,
        expectedMessage: MainActivity.() -> String,
        completeOrder: PublishSubject<ChildOrderAcceptance>.() -> Unit
    ) {
        val apiClient = mock<APIClientV1>()
        val pending = PublishSubject.create<ChildOrderAcceptance>()
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending)
        whenever(apiClient.getPositions()).thenReturn(Observable.never())
        whenever(apiClient.getCollateral()).thenReturn(Observable.never())
        whenever(apiClient.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.never())
        whenever(apiClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.never())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            val order = OrderViewBinding.bind(activity.binding.containerView.getChildAt(0))
            val pager = order.viewPager
            val page = pager.children.filterIsInstance<MarketOrderView>().firstOrNull()
                ?: pager.adapter!!.instantiateItem(pager, 0) as MarketOrderView

            val status = CurrentStatusService(apiClient, Observable.never(), Observable.never(), Observable.never(), apiClient)
            val presenter = MarketOrderPresenter(
                status, activity.module.direct.instance(), activity.resources, activity::showSnackbar
            )
            presenter.takeView(page)
            presenter.amount.value = 0.001
            presenter.buy()
            background.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(presenter.isLoading.value)
            assertTrue(page.isAttachedToWindow)
            activity.tapTab(destination)
            assertFalse(page.isAttachedToWindow)
            presenter.dropView(page)
            assertTrue(presenter.isLoading.value)

            pending.completeOrder()
            background.triggerActions()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(presenter.isLoading.value)
            val message = activity.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
            assertEquals(activity.expectedMessage(), message?.text?.toString())
            verify(apiClient).sendChildOrder(SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.001))
        }
    }

    private fun MainActivity.tapTab(itemId: Int) {
        binding.bottomNavigation.findViewById<View>(itemId).performClick()
    }

    private fun drainStatusRequests() {
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        background.triggerActions()
        main.idle()
    }

    private fun recordingStatusClient(requests: MutableList<String>): APIClientV1 {
        val positions = Observable.just(emptyList<Position>()).doOnSubscribe { requests.add("positions") }
        val collateral = Observable.just<Map<String, Any>>(mapOf("collateral" to 100.0))
            .doOnSubscribe { requests.add("collateral") }
        val orders = Observable.just(emptyList<ChildOrder>()).doOnSubscribe { requests.add("orders") }
        return mock {
            on { getPositions() }.thenReturn(positions)
            on { getCollateral() }.thenReturn(collateral)
            on { getChildOrders("FX_BTC_JPY", count = 500) }.thenReturn(orders)
        }
    }

    private fun MainActivity.isScreenCaptureBlocked(): Boolean =
        (window.attributes.flags and FLAG_SECURE) != 0

    private fun assertDestination(activity: MainActivity, itemId: Int, viewClass: Class<*>, historySize: Int) {
        assertEquals(viewClass, activity.binding.containerView.getChildAt(0).javaClass)
        val navigation = activity.binding.bottomNavigation
        assertEquals(itemId, navigation.selectedItemId)
        assertTrue(navigation.menu.findItem(itemId).isChecked)
        assertEquals(historySize, Flow.get(activity).history.size())
    }

    @Implements(value = Socket::class, isInAndroidSdk = false)
    class DisconnectedSocket {
        @RealObject
        private lateinit var socket: Socket

        @Implementation
        fun connect(): Socket = socket.also { connections.add(it) }

        companion object {
            val connections = mutableListOf<Socket>()
        }
    }

    @Implements(value = APIClient::class, isInAndroidSdk = false)
    class OfflineApi {
        @Implementation
        fun client(): APIClientV1 = mock {
            on { getBoard("FX_BTC_JPY") }.thenReturn(Observable.empty())
            on { getExecutionPrices("FX_BTC_JPY", 500) }.thenReturn(Observable.empty())
            on { getFundingRate("FX_BTC_JPY") }.thenReturn(Observable.empty())
        }
    }
}
