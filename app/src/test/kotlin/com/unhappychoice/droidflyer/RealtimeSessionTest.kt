package com.unhappychoice.droidflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
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
        old.socket.receiveExecution()
        assertTrue(old.positions.hasObservers())
        old.lifecycle.close()
        assertFalse(old.positions.hasObservers())

        val replacement = Session()
        try {
            assertFalse(old.socket.connected())
            assertFalse(old.socket.hasListeners("child_order_events"))
            assertTrue(replacement.socket.connected())

            old.socket.receiveExecution()
            old.status.updateStatus()
            replacement.socket.receiveExecution()
            time.advanceTimeBy(1, TimeUnit.SECONDS)

            assertEquals(1, old.refreshes)
            assertEquals(0, old.notifications)
            assertEquals(2, replacement.refreshes)
            assertEquals(1, replacement.notifications)
        } finally {
            replacement.lifecycle.close()
        }
    }

    @Test
    fun unlinkSignalClearsActiveSessionAndLeavesTheReplacementSocketOpen() {
        val session = Session()
        try {
            session.status.balance.value = 500.0
            session.socket.receiveExecution()

            assertTrue(session.preference.clear())
            time.advanceTimeBy(1, TimeUnit.SECONDS)

            assertEquals(0.0, session.status.balance.value, 0.0)
            assertFalse(session.positions.hasObservers())
            assertFalse(session.socket.connected())
            assertTrue(session.replacement.connected())
            assertEquals(0, session.notifications)
            assertEquals(1, session.refreshes)

            session.status.updateStatus()
            assertEquals(1, session.refreshes)
        } finally {
            session.lifecycle.close()
        }
    }

    private class Session {
        val socket = TestSocket()
        val replacement = TestSocket()
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
        val preference = APITokenPreference(RuntimeEnvironment.getApplication()).apply {
            save(APICredentials("key", "secret"))
        }
        private val client = RealtimeClient(gson, preference, listOf(socket, replacement).iterator()::next)
            .also { socket.finishConnecting() }
        val status = CurrentStatusService(api, client, api)
        val lifecycle = RealtimeSession(client, status).apply { notifyExecutions { notifications++ } }
    }

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false

        override fun connect(): Socket = apply { open = true }

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        override fun emit(event: String, vararg args: Any): Emitter {
            if (event == "auth") (args.last() as Ack).call(JSONObject.NULL)
            return this
        }

        fun finishConnecting() = listeners(EVENT_CONNECT).forEach { it.call() }

        fun receiveExecution() {
            val events = JSONArray("""[{"event_type":"EXECUTION"}]""")
            listeners("child_order_events").forEach { it.call(events) }
        }
    }
}
