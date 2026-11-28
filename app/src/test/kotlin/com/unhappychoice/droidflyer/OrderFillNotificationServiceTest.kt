package com.unhappychoice.droidflyer

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
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
    fun stopsItselfWhenDataSyncTimesOut() {
        val service = Robolectric.buildService(OrderFillNotificationService::class.java).get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
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
    fun notifiesOrderOutcomesAfterCredentialReplacementAndClosesSessionOnDestroy() {
        val socket = TestSocket()
        val accountSockets = mutableListOf<TestSocket>()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val application = RuntimeEnvironment.getApplication()
        val preference = testAPITokenPreference(application)
        var hasOpenOrders = true
        OrderFillNotificationService.openOrdersCheck = { Observable.just(hasOpenOrders) }
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
            time.triggerActions()
            assertFalse(socket.connected())
            val manager = service.getSystemService(NotificationManager::class.java)
            assertNotNull(shadowOf(manager).getNotification(1))

            val previousAccount = accountSockets.last()
            previousAccount.authenticate()
            previousAccount.receiveEvent("EXECUTION")

            preference.save(APICredentials("new-key", "new-secret"))
            val replacement = accountSockets.last()
            assertFalse(previousAccount.connected())
            assertEquals("new-key", replacement.authKey)
            preference.save(APICredentials("new-key", "new-secret"))
            assertEquals(listOf(previousAccount, replacement), accountSockets)
            time.triggerActions()
            assertEquals(1, shadowOf(manager).allNotifications.size)

            replacement.authenticate()
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
    fun notifiesWhenTheLastUntriggeredStopOrderIsCanceled() {
        val account = TestSocket()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
        preference.save(APICredentials("key", "secret"))
        var hasOpenOrders = true
        OrderFillNotificationService.openOrdersCheck = { Observable.just(hasOpenOrders) }
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
            time.triggerActions()
            assertEquals(1, notifications.allNotifications.size)

            hasOpenOrders = false
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertFalse(shadowOf(service).isStoppedBySelf)

            account.receiveParentEvent("CANCEL")
            time.triggerActions()
            time.advanceTimeBy(15, TimeUnit.SECONDS)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(
                listOf(service.getString(R.string.order_notification_canceled, "FX_BTC_JPY")),
                notifications.allNotifications.mapNotNull {
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                }
            )
        } finally {
            controller.destroy()
        }
    }

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
        val firstCheck = PublishSubject.create<Boolean>()
        var checks = 0
        OrderFillNotificationService.openOrdersCheck = {
            if (++checks == 1) firstCheck else Observable.just(checks == 2)
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
            firstCheck.onNext(false)
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

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        fun receiveParentEvent(eventType: String) {
            val events = JSONArray(
                """[{
                    "product_code": "FX_BTC_JPY",
                    "parent_order_id": "JCP20180101-000000-000001",
                    "parent_order_acceptance_id": "JPA20180101-000000-000001",
                    "event_date": "2018-01-01T00:00:00.1234567Z",
                    "event_type": "$eventType"
                }]"""
            )
            listeners("parent_order_events").forEach { it.call(events) }
        }

        fun receiveEvent(eventType: String) {
            val events = JSONArray(
                """[{
                    "product_code": "FX_BTC_JPY",
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
