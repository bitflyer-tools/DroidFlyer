package com.unhappychoice.droidflyer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.OrderFillNotificationService.OrderStatus
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.presentation.executionMessage
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import io.socket.client.Ack
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OrderFillNotificationServiceTest {
    private val time = TestScheduler()
    private val originalSessionFactory = OrderFillNotificationService.sessionFactory

    private val originalOrderCheck = OrderFillNotificationService.openOrdersCheck

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setComputationSchedulerHandler { time }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(time) }
    }

    @After
    fun tearDown() {
        OrderFillNotificationService.sessionFactory = originalSessionFactory
        OrderFillNotificationService.openOrdersCheck = originalOrderCheck
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    @Config(sdk = [36])
    fun stopsAndNotifiesWhenDataSyncTimesOut() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preference = testAPITokenPreference(application)
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(GsonBuilder().create(), preference, TestSocket(), publicFeed = false) { TestSocket() })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)

            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

            assertTrue(shadowOf(service).isStoppedBySelf)
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            val messages = notifications.allNotifications.mapNotNull {
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            }
            assertEquals(listOf(service.getString(R.string.order_fill_monitoring_runtime_limit)), messages)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun notifiesWhenInitialConnectionTimesOut() {
        assertConnectionTimeout { account ->
            account.rejectAuthentication()
            time.triggerActions()
        }
    }

    @Test
    fun notifiesWhenConnectionIsLostWithActiveOrders() {
        assertConnectionTimeout { account ->
            account.authenticate()
            time.triggerActions()
            time.advanceTimeBy(29, TimeUnit.SECONDS)
            account.loseConnection()
            time.triggerActions()
        }
    }

    @Test
    fun restartWithoutExplicitStartStopsWithoutCreatingSession() {
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        assertTrue(preference.clear())
        OrderFillNotificationService.sessionFactory = { error("Unlinked account must not start a session") }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun dropsPreviousAccountFillsBeforeReconnectAndMonitorsReplacement() {
        val reconnect = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { reconnect }
        val socket = TestSocket()
        val accountSockets = mutableListOf<TestSocket>()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val application = RuntimeEnvironment.getApplication()
        val preference = testAPITokenPreference(application)
        var hasOpenOrders = true
        OrderFillNotificationService.openOrdersCheck = {
            Observable.just(if (hasOpenOrders) OrderStatus.ACTIVE else OrderStatus.EMPTY)
        }
        OrderFillNotificationService.sessionFactory = {
            preference.save(APICredentials("key", "secret"))
            val client = RealtimeClient(gson, preference, socket, publicFeed = false) {
                TestSocket().also(accountSockets::add)
            }
            RealtimeSession(client)
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 1))
            RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
            reconnect.triggerActions()
            time.triggerActions()
            assertFalse(socket.connected())
            val manager = service.getSystemService(NotificationManager::class.java)
            assertNotNull(shadowOf(manager).getNotification(1))

            val previousAccount = accountSockets.last()
            previousAccount.authenticate()
            time.triggerActions()
            previousAccount.receiveEvent("EXECUTION")

            assertTrue(preference.saveIfChanged(APICredentials("new-key", "new-secret"), preference.credentialsGeneration))
            assertTrue(previousAccount.connected())
            previousAccount.receiveEvent("EXECUTION")
            time.triggerActions()
            assertEquals(1, shadowOf(manager).allNotifications.size)

            reconnect.triggerActions()
            val replacement = accountSockets.last()
            assertFalse(previousAccount.connected())
            assertEquals("new-key", replacement.authKey)
            preference.save(APICredentials("new-key", "new-secret"))
            reconnect.triggerActions()
            assertEquals(listOf(previousAccount, replacement), accountSockets)
            time.triggerActions()
            assertEquals(1, shadowOf(manager).allNotifications.size)

            replacement.authenticate()
            listOf("EXECUTION", "ORDER_FAILED", "CANCEL").forEach {
                replacement.receiveEvent(it, productCode = "BTC_JPY")
            }
            time.triggerActions()
            assertEquals(1, shadowOf(manager).allNotifications.size)

            replacement.receiveEvent("EXECUTION")
            replacement.receiveEvent("ORDER_FAILED")
            replacement.receiveEvent("CANCEL")
            replacement.receiveEvent("CANCEL_FAILED")
            time.triggerActions()

            val expectedEvent = ChildOrderEvent(
                "FX_BTC_JPY", "JFX20180101-000000-000001F", "JRF20180101-000000-000001",
                "2018-01-01T00:00:00.1234567Z", "EXECUTION", null, null, null, 123L, "BUY",
                BigDecimal("1000000"), 0.01, 0.0, 0.0
            )
            assertEquals(
                listOf(
                    service.executionMessage(expectedEvent),
                    service.getString(R.string.order_notification_rejected, "FX_BTC_JPY"),
                    service.getString(R.string.order_notification_canceled, "FX_BTC_JPY")
                ).sorted(),
                shadowOf(manager).allNotifications.mapNotNull {
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                }.sorted()
            )

            hasOpenOrders = false
            time.advanceTimeBy(30, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
            assertFalse(socket.connected())
            assertTrue(accountSockets.none { it.connected() })
        }
    }

    @Test
    fun notifiesWhenTheLastUntriggeredStopOrderIsCanceled() =
        assertLastParentOrderOutcome("CANCEL", R.string.order_notification_canceled)

    @Test
    fun notifiesWhenTheLastUntriggeredStopOrderIsRejected() =
        assertLastParentOrderOutcome("ORDER_FAILED", R.string.order_notification_rejected)

    @Test
    @Config(sdk = [36])
    fun deniedNotificationPermissionStopsBeforeCheckingOrders() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OrderFillNotificationService.openOrdersCheck = { error("Notifications are disabled") }
        OrderFillNotificationService.sessionFactory = { error("Notifications are disabled") }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 1))
            time.triggerActions()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(shadowOf(service).lastForegroundNotification == null)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun receivesFillWhileFirstOrderCheckIsPendingAndConfirmsEmptyOrdersBeforeStopping() {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        val firstCheck = PublishSubject.create<OrderStatus>()
        var checks = 0
        OrderFillNotificationService.openOrdersCheck = {
            if (++checks == 1) firstCheck else Observable.just(if (checks == 2) OrderStatus.ACTIVE else OrderStatus.EMPTY)
        }
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            assertTrue(account.connected())
            assertEquals(0, checks)

            account.authenticate()
            time.triggerActions()
            assertEquals(1, checks)
            account.receiveEvent("EXECUTION")
            firstCheck.onNext(OrderStatus.EMPTY)
            firstCheck.onComplete()
            time.triggerActions()
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            assertEquals(1, notifications.allNotifications.count {
                it.extras.getCharSequence(Notification.EXTRA_TEXT) != null
            })
            assertFalse(shadowOf(service).isStoppedBySelf)

            time.advanceTimeBy(30, TimeUnit.SECONDS)
            assertEquals(3, checks)
            assertFalse(shadowOf(service).isStoppedBySelf)
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun retriesAfterOrderCheckTimesOutAndKeepsReceivingFills() {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        var checks = 0
        OrderFillNotificationService.openOrdersCheck = {
            if (++checks == 2) Observable.error(TimeoutException()) else Observable.just(OrderStatus.EMPTY)
        }
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            account.authenticate()
            time.triggerActions()
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertEquals(2, checks)
            assertFalse(shadowOf(service).isStoppedBySelf)

            account.receiveEvent("EXECUTION")
            time.triggerActions()
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            assertEquals(1, notifications.allNotifications.count {
                it.extras.getCharSequence(Notification.EXTRA_TEXT) != null
            })

            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertEquals(3, checks)
            assertFalse(shadowOf(service).isStoppedBySelf)
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertEquals(4, checks)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun warnsOnceAndStopsAfterActiveChildOrdersClearWhenParentLookupIsForbidden() {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        val api = mock<APIClientV1>()
        whenever(api.getParentOrders("FX_BTC_JPY", "ACTIVE", count = 1))
            .thenReturn(Observable.error(HttpException(Response.error<Any>(403, "".toResponseBody()))))
        val activeChild = ChildOrder(
            id = 1, childOrderId = "JFX20180101-000000-000001F", productCode = "FX_BTC_JPY",
            side = "BUY", childOrderType = "LIMIT", price = 1_000_000, average_price = 0,
            size = 0.01, childOrderState = "ACTIVE", childOrderAcceptanceId = "JRF20180101-000000-000001",
            outstandingSize = 0.01, cancelSize = 0.0, executedSize = 0.0, totalCommission = 0.0
        )
        whenever(api.getChildOrders("FX_BTC_JPY", count = 1))
            .thenReturn(Observable.just(listOf(activeChild)), Observable.just(listOf(activeChild)), Observable.just(emptyList()))
        OrderFillNotificationService.openOrdersCheck = { OrderFillNotificationService.checkOrderStatus(api) }
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            account.authenticate()
            time.triggerActions()
            assertFalse(shadowOf(service).isStoppedBySelf)

            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            val warning = service.getString(R.string.order_fill_monitoring_parent_permission_warning)
            assertEquals(listOf(warning), notifications.allNotifications.mapNotNull {
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            })

            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertFalse(shadowOf(service).isStoppedBySelf)

            account.receiveEvent("EXECUTION")
            time.triggerActions()
            val fill = service.getString(R.string.order_executed, "FX_BTC_JPY", "BUY", 0.01, "1000000")
            val messages = notifications.allNotifications.mapNotNull {
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            }
            assertEquals(2, messages.size)
            assertEquals(setOf(warning, fill), messages.toSet())

            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertFalse(shadowOf(service).isStoppedBySelf)
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
            verify(api, times(4)).getChildOrders("FX_BTC_JPY", count = 1)
            assertEquals(1, notifications.allNotifications.count {
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == warning
            })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stopsImmediatelyAndNotifiesWhenActiveOrderCredentialsAreRejected() =
        assertActiveOrderAccessDenied(401)

    @Test
    fun stopsImmediatelyAndNotifiesWhenActiveOrderPermissionIsDenied() =
        assertActiveOrderAccessDenied(403)

    @Test
    fun stopsAndNotifiesAfterThreeConsecutiveFailuresFollowingARecovery() {
        assertMonitoringStops(
            expectedChecks = 6,
            messageId = R.string.order_fill_monitoring_check_error
        ) { check ->
            if (check == 3) Observable.just(OrderStatus.ACTIVE) else Observable.error(TimeoutException())
        }
    }

    private fun assertActiveOrderAccessDenied(status: Int) {
        assertMonitoringStops(expectedChecks = 1, messageId = R.string.order_fill_monitoring_permission_error) {
            Observable.error(HttpException(Response.error<Any>(status, "".toResponseBody())))
        }
    }

    private fun assertMonitoringStops(
        expectedChecks: Int,
        messageId: Int,
        result: (Int) -> Observable<OrderStatus>
    ) {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        var checks = 0
        OrderFillNotificationService.openOrdersCheck = { result(++checks) }
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            account.authenticate()
            time.triggerActions()
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            repeat(expectedChecks - 1) {
                assertFalse(shadowOf(service).isStoppedBySelf)
                assertEquals(1, notifications.allNotifications.size)
                time.advanceTimeBy(15, TimeUnit.SECONDS)
            }

            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(
                listOf(service.getString(messageId)),
                notifications.allNotifications.mapNotNull {
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                }
            )
            time.advanceTimeBy(60, TimeUnit.SECONDS)
            assertEquals(expectedChecks, checks)
        } finally {
            controller.destroy()
        }
    }

    private fun assertLastParentOrderOutcome(eventType: String, messageId: Int) {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        var hasOpenOrders = true
        OrderFillNotificationService.openOrdersCheck = {
            Observable.just(if (hasOpenOrders) OrderStatus.ACTIVE else OrderStatus.EMPTY)
        }
        OrderFillNotificationService.sessionFactory = {
            val client = RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account }
            RealtimeSession(client)
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            account.authenticate()
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))

            account.receiveParentEvent("ORDER")
            account.receiveParentEvent(eventType, productCode = "BTC_JPY")
            time.triggerActions()
            assertEquals(1, notifications.allNotifications.size)

            hasOpenOrders = false
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertFalse(shadowOf(service).isStoppedBySelf)

            account.receiveParentEvent(eventType)
            time.triggerActions()
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(
                listOf(service.getString(messageId, "FX_BTC_JPY")),
                notifications.allNotifications.mapNotNull {
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                }
            )
        } finally {
            controller.destroy()
        }
    }

    private fun assertConnectionTimeout(beforeTimeout: (TestSocket) -> Unit) {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        OrderFillNotificationService.openOrdersCheck = { Observable.just(OrderStatus.ACTIVE) }
        OrderFillNotificationService.sessionFactory = {
            RealtimeSession(RealtimeClient(gson, preference, TestSocket(), publicFeed = false) { account })
        }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        val service = controller.get()
        val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
        try {
            service.onStartCommand(startIntent(), 0, 1)
            time.triggerActions()
            beforeTimeout(account)

            time.advanceTimeBy(29, TimeUnit.SECONDS)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertEquals(1, notifications.allNotifications.size)

            time.advanceTimeBy(1, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
        time.advanceTimeBy(30, TimeUnit.SECONDS)
        assertEquals(
            listOf(service.getString(R.string.order_fill_monitoring_connection_timeout)),
            notifications.allNotifications.mapNotNull {
                it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            }
        )
    }

    private fun startIntent(): Intent = Intent().setAction(OrderFillNotificationService.ACTION_START)

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false
        private var ack: Ack? = null
        var authKey: String? = null
            private set

        override fun connect(): Socket = apply {
            open = true
            listeners(EVENT_CONNECT).forEach { it.call() }
        }

        override fun emit(event: String, vararg args: Any): Emitter {
            if (event == "auth") {
                ack = args.last() as Ack
                authKey = (args.first() as JSONObject).getString("api_key")
            }
            if (event == "subscribe") (args.last() as Ack).call(JSONObject.NULL)
            return this
        }

        fun authenticate() = checkNotNull(ack).call(JSONObject.NULL)

        fun rejectAuthentication() = checkNotNull(ack).call("Authentication failed")

        fun loseConnection() {
            disconnect()
            listeners(EVENT_DISCONNECT).forEach { it.call() }
        }

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        fun receiveParentEvent(eventType: String, productCode: String = "FX_BTC_JPY") {
            val events = JSONArray(
                """[{
                    "product_code": "$productCode",
                    "parent_order_id": "JCP20180101-000000-000001",
                    "parent_order_acceptance_id": "JPA20180101-000000-000001",
                    "event_date": "2018-01-01T00:00:00.1234567Z",
                    "event_type": "$eventType"
                }]"""
            )
            listeners("parent_order_events").forEach { it.call(events) }
        }

        fun receiveEvent(eventType: String, productCode: String = "FX_BTC_JPY") {
            val events = JSONArray(
                """[{
                    "product_code": "$productCode",
                    "child_order_id": "JFX20180101-000000-000001F",
                    "child_order_acceptance_id": "JRF20180101-000000-000001",
                    "event_date": "2018-01-01T00:00:00.1234567Z",
                    "event_type": "$eventType",
                    "exec_id": 123,
                    "side": "BUY",
                    "price": 1000000,
                    "size": 0.01,
                    "commission": 0,
                    "sfd": 0
                }]"""
            )
            listeners("child_order_events").forEach { it.call(events) }
        }
    }
}
