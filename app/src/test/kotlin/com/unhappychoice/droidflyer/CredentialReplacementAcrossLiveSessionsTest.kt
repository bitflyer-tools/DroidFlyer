package com.unhappychoice.droidflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.di.loadTradingAccount
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.infrastructure.preference.StopSubmissionPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.TestScheduler
import io.socket.client.Ack
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
class CredentialReplacementAcrossLiveSessionsTest {
    private val scheduler = TestScheduler()
    private val main = TestScheduler()
    private val sessions = mutableListOf<ActivitySession>()
    private val requests = mutableListOf<Request>()
    private val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
    private val stopSubmissions = StopSubmissionPreference(RuntimeEnvironment.getApplication())
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val transport = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        val body = when (request.url.encodedPath) {
            "/v1/board" -> """{"mid_price":100.0,"bids":[],"asks":[]}"""
            "/v1/me/getcollateral" -> """{"collateral":0}"""
            "/v1/me/sendchildorder" -> """{"child_order_acceptance_id":"accepted"}"""
            else -> "[]"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Test response")
            .body(body.toResponseBody())
            .build()
    }.build()

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setSingleSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(main) }
    }

    @After
    fun tearDown() {
        sessions.forEach(ActivitySession::close)
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun replacingCredentialsSwitchesEveryLiveSessionAndRestoresPendingStops() {
        preference.save(APICredentials("account-a-key", "account-a-secret"))
        val oldGuard = stopSubmissions.forApiKey("account-a-key")
        val replacementGuard = stopSubmissions.forApiKey("account-b-key")
        assertTrue(replacementGuard.reserve("pending-on-b"))
        val instance1 = ActivitySession().also(sessions::add)
        val instance2 = ActivitySession().also(sessions::add)
        runSchedulers()
        assertFalse(instance1.status.privateState.stopOrders.reconcilingSubmission.value)
        assertFalse(instance2.status.privateState.stopOrders.reconcilingSubmission.value)
        assertTrue(oldGuard.reserve("pending-on-a"))
        val order = SendChildOrderRequest(childOrderType = "LIMIT", side = "BUY", price = 100L, size = 0.01)

        val liveSessions = listOf(instance1, instance2)
        val pendingOrders = liveSessions.map { checkNotNull(it.status.privateState.prepareOrder(order)) }
        val oldSockets = liveSessions.map { it.accountSockets.single() }

        saveReplacementCredentials(instance2.settings)

        liveSessions.zip(pendingOrders).forEach { (instance, pendingOrder) ->
            assertNull(instance.status.privateState.submitOrder(pendingOrder))
            assertEquals(2, instance.accountSockets.size)
        }
        oldSockets.forEach { assertFalse(it.connected()) }

        assertPendingStopRestored(instance1, instance2)
        assertNull(replacementGuard.pending())
        assertEquals("pending-on-a", oldGuard.pending())
        assertOrdersUseCredentials(instance1, "account-b-key")
        assertOrdersUseCredentials(instance2, "account-b-key")
    }

    @Test
    fun consecutiveReplacementsApplyOnlyTheLatestLoadedAccount() {
        preference.save(APICredentials("account-a-key", "account-a-secret"))
        val liveSessions = List(2) { ActivitySession().also(sessions::add) }
        runSchedulers()
        val switches = liveSessions.map { it.status.privateState.accountChanges.test() }

        assertTrue(preference.saveIfChanged(APICredentials("account-b-key", "account-b-secret"), 0L))
        scheduler.triggerActions() // Load B, but leave its result on the UI queue.
        assertTrue(preference.saveIfChanged(APICredentials("account-c-key", "account-c-secret"), 1L))
        scheduler.triggerActions() // Queue C behind the stale B result.
        runSchedulers()

        switches.forEach { it.assertValueCount(1) }
        liveSessions.forEach { assertOrdersUseCredentials(it, "account-c-key") }
    }

    @Test
    fun clientBuiltAfterReplacementCannotSendUsingTheOldSnapshot() {
        preference.save(APICredentials("account-a-key", "account-a-secret"))
        val oldSnapshot = preference.readSnapshot()
        assertTrue(preference.saveIfChanged(APICredentials("account-b-key", "account-b-secret"), oldSnapshot.generation))

        val api = APIClient(gson, preference, transport)
        checkNotNull(api.privateStatusClient(oldSnapshot)).getPositions().test()
            .assertNoValues().assertError(IOException::class.java)
        assertTrue(requests.isEmpty())

        checkNotNull(api.privateStatusClient(preference.readSnapshot())).getPositions().test().assertValueCount(1)
        assertEquals("account-b-key", requests.single().header("Access-Key"))
    }

    private fun runSchedulers() {
        scheduler.triggerActions()
        main.triggerActions()
        scheduler.triggerActions()
        main.triggerActions()
    }

    private fun saveReplacementCredentials(settings: SettingsPresenter) {
        settings.key.value = "account-b-key"
        settings.secret.value = "account-b-secret"
        val saved = settings.saveCredentials().test()
        runSchedulers()
        saved.assertValueCount(1).assertComplete()
    }

    private fun assertPendingStopRestored(vararg sessions: ActivitySession) {
        val stopServices = sessions.map { it.status.privateState.stopOrders }
        stopServices.forEach { stopOrders ->
            assertTrue(stopOrders.orderEventsReady)
            assertTrue(stopOrders.reconcilingSubmission.value)
        }
        val recover = checkNotNull(stopServices.first().confirmSubmissionRecovery())
        assertTrue(recover())
        assertNull(stopServices.last().confirmSubmissionRecovery())
    }

    private fun assertOrdersUseCredentials(instance: ActivitySession, key: String) {
        requests.clear()
        val order = SendChildOrderRequest(childOrderType = "LIMIT", side = "BUY", price = 100L, size = 0.01)
        val prepared = checkNotNull(instance.status.privateState.prepareOrder(order))
        val submission = checkNotNull(instance.status.privateState.submitOrder(prepared)).test()
        runSchedulers()
        submission.assertValueCount(1).assertComplete()
        val request = requests.single { it.url.encodedPath == "/v1/me/sendchildorder" }
        assertEquals(key, request.header("Access-Key"))
    }

    private inner class ActivitySession {
        private val api = APIClient(gson, preference, transport)
        val settings = SettingsPresenter(
            api, preference, CryptoCfdAccountPreference(RuntimeEnvironment.getApplication())
        )
        val accountSockets = mutableListOf<TestSocket>()
        private val client = RealtimeClient(gson, preference, TestSocket()) { TestSocket().also(accountSockets::add) }
        val status = CurrentStatusService(api.client(), client, null)
        private val session = RealtimeSession(client, status) { loadTradingAccount(api, stopSubmissions, it) }

        fun close() {
            session.close()
        }
    }

    private class TestSocket : Socket(Manager(), "/", Manager.Options()) {
        private var open = false

        override fun connect(): Socket = apply {
            open = true
            listeners(EVENT_CONNECT).forEach { it.call() }
        }

        override fun emit(event: String, vararg args: Any): Emitter {
            // Private authentication and subscriptions both require acknowledgements.
            (args.lastOrNull() as? Ack)?.call(JSONObject.NULL)
            return this
        }

        override fun disconnect(): Socket = apply { open = false }

        override fun connected(): Boolean = open
    }
}
