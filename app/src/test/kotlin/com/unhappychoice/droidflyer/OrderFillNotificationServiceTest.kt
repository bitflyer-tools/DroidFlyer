package com.unhappychoice.droidflyer

import android.app.Notification
import android.app.NotificationManager
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OrderFillNotificationServiceTest {
    private val time = TestScheduler()
    private val originalSessionFactory = OrderFillNotificationService.sessionFactory

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setComputationSchedulerHandler { time }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(time) }
    }

    @After
    fun tearDown() {
        OrderFillNotificationService.sessionFactory = originalSessionFactory
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun startsForegroundAndNotifiesOnExecutionThenClosesSessionOnDestroy() {
        val socket = TestSocket()
        val accountSockets = mutableListOf<TestSocket>()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication()).apply {
            save(APICredentials("key", "secret"))
        }
        val api = Proxy.newProxyInstance(
            APIClientV1::class.java.classLoader,
            arrayOf(APIClientV1::class.java)
        ) { _, _, _ -> Observable.empty<Any>() } as APIClientV1
        val client = RealtimeClient(gson, preference, socket) { TestSocket().also(accountSockets::add) }
        val status = CurrentStatusService(api, client, api)
        OrderFillNotificationService.sessionFactory = { RealtimeSession(client, status) }

        val controller = Robolectric.buildService(OrderFillNotificationService::class.java).create()
        try {
            val service = controller.get()
            val manager = service.getSystemService(NotificationManager::class.java)
            assertNotNull(shadowOf(manager).getNotification(1))

            val account = accountSockets.single()
            account.authenticate()
            account.receiveExecution()
            time.triggerActions()

            val fillNotification = shadowOf(manager).getNotification(2)
            assertNotNull(fillNotification)
            val expectedEvent = ChildOrderEvent(
                "FX_BTC_JPY", "JFX20180101-000000-000001F", "JRF20180101-000000-000001",
                "2018-01-01T00:00:00.1234567Z", "EXECUTION", null, null, null, 123L, "BUY",
                BigDecimal("1000000"), 0.01, 0.0, 0.0
            )
            assertEquals(
                service.executionMessage(expectedEvent),
                fillNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            )
        } finally {
            controller.destroy()
            assertFalse(socket.connected())
            assertFalse(accountSockets.single().connected())
        }
    }

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false
        private var ack: Ack? = null

        override fun connect(): Socket = apply {
            open = true
            listeners(EVENT_CONNECT).forEach { it.call() }
        }

        override fun emit(event: String, vararg args: Any): Emitter {
            if (event == "auth") ack = args.last() as Ack
            return this
        }

        fun authenticate() = checkNotNull(ack).call(JSONObject.NULL)

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        fun receiveExecution() {
            val events = JSONArray(
                """[{
                    "product_code": "FX_BTC_JPY",
                    "child_order_id": "JFX20180101-000000-000001F",
                    "child_order_acceptance_id": "JRF20180101-000000-000001",
                    "event_date": "2018-01-01T00:00:00.1234567Z",
                    "event_type": "EXECUTION",
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
