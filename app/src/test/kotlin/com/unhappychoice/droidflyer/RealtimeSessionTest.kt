package com.unhappychoice.droidflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import io.socket.client.Manager
import io.socket.client.Socket
import org.json.JSONArray
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
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(time) }
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
        old.status.updateStatus()
        old.lifecycle.close()
        assertFalse(old.positions.hasObservers())

        val replacement = Session()
        try {
            assertFalse(old.socket.connected())
            assertFalse(old.socket.hasListeners("child_order_events"))
            assertFalse(old.client.childOrderEvents.hasObservers())
            assertFalse(old.client.parentOrderEvents.hasObservers())
            assertTrue(replacement.socket.connected())

            old.socket.receiveExecution()
            old.status.updateStatus()
            old.status.pollStatus()
            replacement.socket.receiveExecution()
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
        val client = RealtimeClient(gson, APITokenPreference(RuntimeEnvironment.getApplication()), socket)
        val status = CurrentStatusService(api, client, api)
        val lifecycle = RealtimeSession(client, status).apply { notifyExecutions { notifications++ } }
    }

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false

        override fun connect(): Socket = apply { open = true }

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open

        fun receiveExecution() {
            val events = JSONArray("""[{"event_type":"EXECUTION"}]""")
            listeners("child_order_events").forEach { it.call(events) }
        }
    }
}
