package com.unhappychoice.droidflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.Collateral
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import io.socket.client.AckWithTimeout
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
class RealtimeSessionTest {
    private val time = TestScheduler()

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setComputationSchedulerHandler { time }
        RxAndroidPlugins.setMainThreadSchedulerHandler { time }
    }

    @After
    fun tearDown() {
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun replacementSessionAloneRefreshesStatusAndDeliversExecutions() {
        val old = Session()
        assertEquals(1, old.refreshes)
        old.accountSockets.single().receiveExecution()
        assertTrue(old.positions.hasObservers())
        old.status.updateStatus()
        old.lifecycle.close()
        assertFalse(old.positions.hasObservers())

        val replacement = Session()
        try {
            assertFalse(old.socket.connected())
            assertFalse(old.accountSockets.single().connected())
            assertFalse(old.accountSockets.single().hasListeners("child_order_events"))
            assertTrue(replacement.socket.connected())

            old.accountSockets.single().receiveExecution()
            old.status.updateStatus()
            old.status.pollStatus()
            replacement.accountSockets.single().receiveExecution()
            time.advanceTimeBy(1, TimeUnit.SECONDS)

            assertEquals(1, old.refreshes)
            assertEquals(0, old.notifications)
            assertEquals(1, replacement.refreshes)
            replacement.positions.onComplete()
            time.triggerActions()
            assertEquals(2, replacement.refreshes)
            assertEquals(1, replacement.notifications)
        } finally {
            replacement.lifecycle.close()
        }
    }

    @Test
    fun unlinkSignalClearsActiveSessionAndKeepsPublicSocketOpen() {
        val session = Session()
        try {
            session.status.privateState.collateral.value = Collateral(500.0)
            val account = session.accountSockets.single()
            account.receiveExecution()

            assertTrue(session.preference.clear())
            time.advanceTimeBy(1, TimeUnit.SECONDS)

            assertNull(session.status.privateState.collateral.value.amount)
            assertFalse(session.positions.hasObservers())
            assertFalse(account.connected())
            assertTrue(session.socket.connected())
            assertEquals(listOf(account), session.accountSockets)
            assertEquals(0, session.notifications)
            assertEquals(1, session.refreshes)

            session.status.updateStatus()
            assertEquals(1, session.refreshes)
        } finally {
            session.lifecycle.close()
        }
    }

    @Test
    fun queuedExecutionFromPreviousAccountIsDroppedBeforeNotification() {
        val session = Session()
        try {
            session.accountSockets.single().receiveExecution()
            session.client.updateAccount {
                session.preference.save(APICredentials("replacement", "secret"))
            }
            val replacement = session.accountSockets.last()
            replacement.receiveExecution()
            time.advanceTimeBy(1, TimeUnit.SECONDS)
            assertEquals(0, session.notifications)
            assertEquals(1, session.refreshes)

            replacement.authenticate()
            replacement.receiveExecution()
            time.triggerActions()
            assertEquals(1, session.notifications)
        } finally {
            session.lifecycle.close()
        }
    }

    private class Session {
        val socket = TestSocket()
        val positions = PublishSubject.create<List<Position>>()
        var refreshes = 0
        var notifications = 0

        private val api = Proxy.newProxyInstance(
            APIClientV1::class.java.classLoader,
            arrayOf(APIClientV1::class.java)
        ) { _, method, _ ->
            if (method.name == "getPositions") {
                refreshes++
                positions
            } else {
                Observable.empty<Any>()
            }
        } as APIClientV1
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication()).apply {
            save(APICredentials("initial", "secret"))
        }
        val accountSockets = mutableListOf<TestSocket>()
        val client = RealtimeClient(gson, preference, socket) {
            TestSocket().also(accountSockets::add)
        }.also { accountSockets.single().authenticate() }
        val status = CurrentStatusService(api, client, api)
        val lifecycle = RealtimeSession(client, status) {
            it.credentials.takeIf { it.isConfigured }?.let { api to StopSubmissionGuard.inMemory() }
        }.apply { notifyExecutions { notifications++ } }
    }

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false

        private var ack: AckWithTimeout? = null

        override fun connect(): Socket = apply {
            open = true
            listeners(EVENT_CONNECT).forEach { it.call() }
        }

        override fun emit(event: String, vararg args: Any): Emitter {
            val acknowledgment = args.lastOrNull() as? AckWithTimeout ?: return this
            if (event == "auth") {
                acknowledgment.cancelTimer()
                ack = acknowledgment
            } else {
                acknowledgment.call(JSONObject.NULL)
            }
            return this
        }

        fun authenticate() = checkNotNull(ack).call(JSONObject.NULL)

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        fun receiveExecution() {
            val events = JSONArray("""[{"event_type":"EXECUTION"}]""")
            listeners("child_order_events").forEach { it.call(events) }
        }
    }
}
