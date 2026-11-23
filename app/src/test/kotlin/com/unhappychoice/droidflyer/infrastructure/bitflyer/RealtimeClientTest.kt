package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.MarketPrice
import com.unhappychoice.droidflyer.domain.service.TradingTotals
import com.unhappychoice.droidflyer.extension.splitByComma
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.presentation.view.CurrentStatusView
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RealtimeClientTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val socket = RecordingSocket()
    private val accountSockets = mutableListOf<RecordingSocket>()
    private val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())

    private val authentication = TestScheduler()

    @Before
    fun pauseAuthentication() {
        RxJavaPlugins.setIoSchedulerHandler { authentication }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
    }

    @Test
    fun subscribesToBoardAndExecutionsAndKeepsAuthenticatedSubscriptions() {
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("api_token", Context.MODE_PRIVATE)
        stored.edit().putString("key", "key").putString("secret", "secret").commit()
        val client = createClient()
        socket.receive(Socket.EVENT_CONNECT)
        assertEquals(
            listOf("lightning_board_FX_BTC_JPY", "lightning_board_snapshot_FX_BTC_JPY", "lightning_executions_FX_BTC_JPY"),
            socket.subscriptions
        )
        assertFalse(socket.hasListeners("lightning_ticker_FX_BTC_JPY"))

        assertEquals(emptyList<RecordingSocket>(), accountSockets)
        assertEquals("key", stored.getString("key", null))
        authentication.triggerActions()
        assertFalse(stored.contains("key"))
        val account = accountSockets.single()
        account.receive(Socket.EVENT_CONNECT)
        account.authAck!!.call(JSONObject.NULL)
        assertEquals(listOf("child_order_events", "parent_order_events"), account.subscriptions)

        val board = client.board.test()
        val snapshot = client.boardSnapshot.test()
        val childOrders = client.childOrderEvents.map { it.value }.test()
        val parentOrders = client.parentOrderEvents.map { it.value }.test()
        val boardPayload = JSONObject("""{"mid_price":123456.0,"bids":[],"asks":[]}""")
        socket.receive("lightning_board_FX_BTC_JPY", boardPayload)
        socket.receive("lightning_board_snapshot_FX_BTC_JPY", boardPayload)
        account.receive("child_order_events", JSONArray())
        account.receive("parent_order_events", JSONArray())
        val expectedBoard = Board(123456.0, emptyList(), emptyList())
        board.assertValue(expectedBoard)
        snapshot.assertValue(expectedBoard)
        childOrders.assertValue(emptyList())
        parentOrders.assertValue(emptyList())
    }

    @Test
    fun publishesPrivateExecutionBatchWithFractionalAndIntegralPrices() {
        preference.save(APICredentials("key", "secret"))
        val client = createClient()
        authentication.triggerActions()
        val account = accountSockets.single()
        account.receive(Socket.EVENT_CONNECT)
        account.authAck!!.call(JSONObject.NULL)
        val observer = client.childOrderEvents.map { it.value }.test()
        val payload = JSONArray(
            """
                [{"product_code":"ETH_BTC","event_type":"EXECUTION","price":0.02744},
                 {"product_code":"BTC_JPY","event_type":"EXECUTION","price":10000000}]
            """.trimIndent()
        )

        account.receive("child_order_events", payload)

        observer.assertValue { events ->
            events.all { it.isExecution() } &&
                events.map { it.productCode to it.price } ==
                listOf("ETH_BTC" to BigDecimal("0.02744"), "BTC_JPY" to BigDecimal("10000000"))
        }
    }

    @Test
    fun disconnectCancelsPendingCredentialLoad() {
        val stored = RuntimeEnvironment.getApplication().getSharedPreferences("api_token", Context.MODE_PRIVATE)
        stored.edit().putString("key", "key").putString("secret", "secret").commit()
        val client = createClient()

        client.disconnect()
        authentication.triggerActions()

        assertEquals("key", stored.getString("key", null))
        assertEquals(emptyList<RecordingSocket>(), accountSockets)
    }

    @Test
    fun pendingStartupLoadKeepsTheAccountAlreadyApplied() {
        val client = createClient()
        client.updateAccount { preference.save(APICredentials("replacement", "secret")) }
        val account = accountSockets.single()
        account.receive(Socket.EVENT_CONNECT)
        val acknowledge = checkNotNull(account.authAck)

        authentication.triggerActions()
        acknowledge.call(JSONObject.NULL)

        assertEquals(listOf(account), accountSockets)
        assertEquals(listOf("child_order_events", "parent_order_events"), account.subscriptions)
    }

    @Test
    fun ignoresAuthenticationAcknowledgementAfterDisconnect() {
        preference.save(APICredentials("key", "secret"))
        val client = createClient()
        authentication.triggerActions()
        socket.receive(Socket.EVENT_CONNECT)
        val account = accountSockets.single()
        account.receive(Socket.EVENT_CONNECT)
        val acknowledge = checkNotNull(account.authAck)

        client.disconnect()
        acknowledge.call(JSONObject.NULL)

        assertEquals(emptyList<String>(), account.subscriptions)
    }

    @Test
    fun accountSwitchRejectsStalePrivateCallbacksAndKeepsPublicData() {
        preference.save(APICredentials("first", "secret"))
        val client = createClient()
        authentication.triggerActions()
        socket.receive(Socket.EVENT_CONNECT)
        val old = accountSockets.single()
        old.receive(Socket.EVENT_CONNECT)
        old.authAck!!.call(JSONObject.NULL)
        val staleListener = old.listeners("child_order_events").single()
        // Reconnect with an authentication request still pending when the account changes.
        old.receive(Socket.EVENT_DISCONNECT)
        old.receive(Socket.EVENT_CONNECT)
        val staleAck = checkNotNull(old.authAck)
        val childOrders = client.childOrderEvents.map { it.value }.test()
        val parentOrders = client.parentOrderEvents.map { it.value }.test()
        val board = client.board.test()

        client.updateAccount { preference.save(APICredentials("second", "secret")) }
        val replacement = accountSockets.last()
        replacement.receive(Socket.EVENT_CONNECT)
        staleListener.call(JSONArray())
        staleAck.call(JSONObject.NULL)
        replacement.receive("child_order_events", JSONArray())
        replacement.receive("parent_order_events", JSONArray())
        socket.receive("lightning_board_FX_BTC_JPY", JSONObject("""{"mid_price":100,"bids":[],"asks":[]}"""))

        assertFalse(old.hasListeners("child_order_events"))
        assertEquals(listOf("child_order_events", "parent_order_events"), old.subscriptions)
        assertEquals(emptyList<String>(), replacement.subscriptions)
        childOrders.assertNoValues()
        parentOrders.assertNoValues()
        board.assertValue(Board(100.0, emptyList(), emptyList()))

        replacement.authAck!!.call(JSONObject.NULL)
        replacement.receive("child_order_events", JSONObject())
        replacement.receive("parent_order_events", JSONObject())
        staleListener.call(JSONArray())
        replacement.receive("child_order_events", JSONArray())
        replacement.receive("parent_order_events", JSONArray())
        childOrders.assertValue(emptyList())
        parentOrders.assertValue(emptyList())
        assertEquals(listOf("child_order_events", "parent_order_events"), replacement.subscriptions)

        client.updateAccount {}
        assertEquals(listOf(old, replacement), accountSockets)
    }

    @Test
    fun failedAuthenticationKeepsPrivateEventsDisabledUntilSuccessfulRetry() {
        preference.save(APICredentials("key", "secret"))
        val client = createClient()
        authentication.triggerActions()
        val account = accountSockets.single()
        val orders = client.childOrderEvents.test()

        account.receive(Socket.EVENT_CONNECT)
        account.authAck!!.call("denied")
        account.receive("child_order_events", JSONArray())

        orders.assertNoValues()
        assertEquals(emptyList<String>(), account.subscriptions)

        client.updateAccount {}
        val retry = accountSockets.last()
        retry.receive(Socket.EVENT_CONNECT)
        retry.authAck!!.call(JSONObject.NULL)
        retry.receive("child_order_events", JSONArray())
        orders.assertValueCount(1)
    }

    @Test
    fun resavingCredentialsRetriesPendingAuthenticationAndIgnoresLateAcknowledgement() {
        preference.save(APICredentials("key", "secret"))
        val client = createClient()
        authentication.triggerActions()
        val original = accountSockets.single()
        val orders = client.childOrderEvents.test()

        original.receive(Socket.EVENT_CONNECT)
        val lateAck = checkNotNull(original.authAck)

        client.updateAccount {}
        assertEquals(2, accountSockets.size)
        val retry = accountSockets.last()
        retry.receive(Socket.EVENT_CONNECT)
        val retryAck = checkNotNull(retry.authAck)

        lateAck.call(JSONObject.NULL)
        assertEquals(emptyList<String>(), original.subscriptions)
        assertEquals(emptyList<String>(), retry.subscriptions)
        retry.receive("child_order_events", JSONArray())
        orders.assertNoValues()

        retryAck.call(JSONObject.NULL)
        assertEquals(listOf("child_order_events", "parent_order_events"), retry.subscriptions)
        retry.receive("child_order_events", JSONArray())
        orders.assertValueCount(1)
    }

    @Test
    fun valuationsWaitForFirstValidExecutionAfterPrivateDataLoads() {
        val client = createClient()
        val api = mock<APIClientV1>()
        val position = Position("FX_BTC_JPY", "BUY", 10_000_000L, 0.1, 0.0, 0.0, 0.0, 1L, 0.0)
        whenever(api.getPositions()).thenReturn(Observable.just(listOf(position)))
        val collateral = PublishSubject.create<Map<String, Any>>()
        whenever(api.getCollateral()).thenReturn(collateral)
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())

        val clock = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { clock }
        try {
            val service = CurrentStatusService(api, client, api)
            socket.receive(Socket.EVENT_CONNECT)
            clock.triggerActions()
            val totals = service.tradingTotals.test()
            assertEquals(listOf(position), service.position.value)
            assertNull(service.currentPrice.value.yen)
            collateral.onNext(mapOf("collateral" to 200_000.0))
            clock.triggerActions()
            totals.assertValues(
                TradingTotals(null, null, null, null),
                TradingTotals(null, null, 200_000L, null)
            )

            val channel = "lightning_executions_FX_BTC_JPY"
            socket.receive(channel, JSONArray())
            socket.receive(channel, JSONArray("""[{"price":0.0},{"price":-1.0}]"""))
            clock.triggerActions()
            assertNull(service.currentPrice.value.yen)
            totals.assertValueCount(2)

            socket.receive(channel, JSONArray("""[{"price":0.0},{"price":10100000.0}]"""))
            clock.triggerActions()
            assertEquals(10_100_000.0, service.currentPrice.value.yen!!, 0.0)
            totals.assertValues(
                TradingTotals(null, null, null, null),
                TradingTotals(null, null, 200_000L, null),
                TradingTotals(10_000L, 100_000L, 210_000L, 10_100_000L)
            ).assertNoErrors()

            socket.receive(channel, JSONArray())
            clock.triggerActions()
            assertEquals(10_100_000.0, service.currentPrice.value.yen!!, 0.0)

            socket.receive(channel, JSONArray("""[{"price":10200000.0}]"""))
            clock.triggerActions()
            assertEquals(10_200_000.0, service.currentPrice.value.yen!!, 0.0)
            totals.dispose()
            service.dispose()
            client.disconnect()
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun disconnectClearsPublicQuotesAndRestoresBoardFallbackWithoutPrivateCredentials() {
        val client = createClient()
        val api = mock<APIClientV1>()
        val fallback = Board(10_050_000.0, listOf(Order(10_000_000, 1.0)), listOf(Order(10_100_000, 1.0)))
        val delayedFallback = PublishSubject.create<Board>()
        whenever(api.getBoard("FX_BTC_JPY"))
            .thenReturn(Observable.empty(), Observable.just(fallback), delayedFallback.take(1))
        val clock = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxJavaPlugins.setSingleSchedulerHandler { clock }
        RxAndroidPlugins.setMainThreadSchedulerHandler { clock }
        val service = CurrentStatusService(api, client, null)
        val snapshots = "lightning_board_snapshot_FX_BTC_JPY"
        try {
            socket.receive(Socket.EVENT_CONNECT)
            socket.receive(snapshots, JSONObject(
                """{"mid_price":9990000,"bids":[{"price":9980000,"size":1}],"asks":[{"price":10000000,"size":1}]}"""
            ))
            socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":10000000.0}]"""))
            clock.advanceTimeBy(300, MILLISECONDS)
            assertEquals(MarketPrice(10_000_000.0), service.currentPrice.value)
            assertEquals(10_000_000L, service.buyPrice.value)
            assertEquals(9_980_000L, service.sellPrice.value)
            assertEquals(listOf(Order(10_000_000, 1.0)), service.board.value.asks)

            socket.receive(Socket.EVENT_DISCONNECT)
            clock.triggerActions()
            assertEquals(MarketPrice(null), service.currentPrice.value)
            assertEquals(0L, service.buyPrice.value)
            assertEquals(0L, service.sellPrice.value)
            assertEquals(Board(0.0, emptyList(), emptyList()), service.board.value)

            service.pollStatus()
            clock.advanceTimeBy(300, MILLISECONDS)
            assertEquals(fallback, service.board.value)
            assertEquals(10_100_000L, service.buyPrice.value)
            assertEquals(10_000_000L, service.sellPrice.value)

            service.pollStatus()
            clock.triggerActions()
            assertTrue(delayedFallback.hasObservers())
            socket.receive(Socket.EVENT_CONNECT)
            socket.receive(snapshots, JSONObject(
                """{"mid_price":10150000,"bids":[{"price":10100000,"size":1}],"asks":[{"price":10200000,"size":1}]}"""
            ))
            clock.advanceTimeBy(300, MILLISECONDS)
            delayedFallback.onNext(fallback)
            clock.advanceTimeBy(300, MILLISECONDS)
            assertEquals(10_200_000L, service.buyPrice.value)
            assertEquals(10_100_000L, service.sellPrice.value)
            assertEquals(listOf(Order(10_200_000, 1.0)), service.board.value.asks)
        } finally {
            service.dispose()
            client.disconnect()
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun disconnectExpiresHeaderValuationsUntilAFreshExecution() {
        val client = createClient()
        val api = mock<APIClientV1>()
        whenever(api.getFundingRate("FX_BTC_JPY")).thenReturn(Observable.empty())
        val position = Position("FX_BTC_JPY", "BUY", 11_000_000L, 0.1, 0.0, 0.0, 0.0, 1L, 0.0)
        whenever(api.getPositions()).thenReturn(Observable.just(emptyList()), Observable.just(listOf(position)))
        whenever(api.getCollateral()).thenReturn(
            Observable.just(mapOf("collateral" to 500_000.0)),
            Observable.just(mapOf("collateral" to 600_000.0))
        )
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())

        val clock = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { clock }
        try {
            val service = CurrentStatusService(api, client, api)
            clock.triggerActions()
            Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
                val host = controller.get()
                val header = CurrentStatusView(host, null)
                header.inject(DI { bindInstance { service } })
                host.setContentView(header)
                val totals = service.tradingTotals.test()

                socket.receive(Socket.EVENT_CONNECT)
                socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":10000000.0}]"""))
                clock.triggerActions()
                assertEquals("${10_000_000L.splitByComma()} JPY", header.text(R.id.currentPrice))
                assertFalse(header.findViewById<TextView>(R.id.profit).isVisible)
                assertFalse(header.findViewById<TextView>(R.id.spread).isVisible)

                socket.receive(Socket.EVENT_DISCONNECT)
                clock.triggerActions()
                assertEquals("—", header.text(R.id.currentPrice))
                service.updateStatus()
                clock.triggerActions()
                socket.receive(Socket.EVENT_CONNECT)
                socket.receive("lightning_executions_FX_BTC_JPY", JSONArray())
                socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":0.0}]"""))
                clock.advanceTimeBy(100, MILLISECONDS)
                totals.assertValues(
                    TradingTotals(null, null, 500_000L, null),
                    TradingTotals(0L, 10_000_000L, 500_000L, 10_000_000L),
                    TradingTotals(null, null, 500_000L, null),
                    TradingTotals(null, null, 600_000L, null)
                )
                assertEquals("—", header.text(R.id.currentPrice))
                assertTrue(header.findViewById<TextView>(R.id.profit).isVisible)
                assertTrue(header.findViewById<TextView>(R.id.spread).isVisible)
                assertEquals("—", header.text(R.id.profit))
                assertEquals("—", header.text(R.id.spread))
                assertEquals("Collateral: ${600_000L.splitByComma()} JPY", header.text(R.id.balance))
                assertEquals("${11_000_000L.splitByComma()} JPY / 0.1 ɃFX", header.text(R.id.position))

                socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":12000000.0}]"""))
                clock.triggerActions()
                totals.assertValueAt(4, TradingTotals(100_000L, 1_000_000L, 700_000L, 12_000_000L))
                assertEquals("${12_000_000L.splitByComma()} JPY", header.text(R.id.currentPrice))
                assertEquals(100_000L.splitByComma(), header.text(R.id.profit))
                assertEquals(1_000_000L.splitByComma(), header.text(R.id.spread))
                assertEquals("${700_000L.splitByComma()} JPY", header.text(R.id.balance))
                totals.dispose()
            }
            service.dispose()
            client.disconnect()
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun publishesBoardDeltaWithoutIndentingPayload() {
        val client = createClient()
        val payload = object : JSONObject(
            """{"mid_price":100,"bids":[{"price":99,"size":0}],"asks":[{"price":101,"size":0.25}]}"""
        ) {
            override fun toString(indentSpaces: Int): String {
                assertEquals(0, indentSpaces)
                return super.toString(indentSpaces)
            }
        }
        val observer = client.board.test()

        socket.receive("lightning_board_FX_BTC_JPY", payload)

        observer.assertValue(Board(100.0, listOf(Order(99, 0.0)), listOf(Order(101, 0.25))))
    }

    @Test
    fun publishesExecutionsAfterInvalidPayload() {
        val client = createClient()
        val observer = client.executions.test()
        val callback = socket.listeners("lightning_executions_FX_BTC_JPY").single()

        callback.call(JSONArray("""[{"price":101.0}]"""))
        callback.call(JSONObject("""{"price":102.0}"""))
        callback.call(JSONArray("""[{"price":103.0}]"""))

        observer.assertValueCount(2).assertNoErrors().assertNotComplete()
        assertEquals(listOf(101.0, 103.0), observer.values().map { it.single().price })
    }

    @Test
    fun publishesExecutionBatchWithoutIndentingPayload() {
        val client = createClient()
        val payload = object : JSONArray(
            """
                [{"id":123,"side":"BUY","price":101,"size":0.25,"exec_date":"2020-01-01T00:00:00Z",
                  "buy_child_order_acceptance_id":"buy-123","sell_child_order_acceptance_id":"sell-123"},
                 {"id":124,"side":"SELL","price":99,"size":0.5,"exec_date":"2020-01-01T00:00:01Z",
                  "buy_child_order_acceptance_id":"buy-124","sell_child_order_acceptance_id":"sell-124"}]
            """.trimIndent()
        ) {
            override fun toString(indentSpaces: Int): String {
                assertEquals(0, indentSpaces)
                return super.toString(indentSpaces)
            }
        }
        val observer = client.executions.test()

        socket.receive("lightning_executions_FX_BTC_JPY", payload)

        observer.assertValue(
            listOf(
                Execution(123, "BUY", 101.0, 0.25, Date(1577836800000L), "buy-123", "sell-123"),
                Execution(124, "SELL", 99.0, 0.5, Date(1577836801000L), "buy-124", "sell-124")
            )
        )
    }

    private fun CurrentStatusView.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun createClient() = RealtimeClient(gson, preference, socket) {
        RecordingSocket().also(accountSockets::add)
    }

    private class RecordingSocket : Socket(Manager(), "/", Manager.Options()) {
        val subscriptions = mutableListOf<String>()
        var authAck: Ack? = null

        override fun connect(): Socket = this

        override fun disconnect(): Socket = this

        override fun emit(event: String, vararg args: Any): Emitter {
            when (event) {
                "subscribe" -> subscriptions.add(args.single() as String)
                "auth" -> authAck = args.last() as Ack
            }
            return this
        }

        fun receive(event: String, vararg args: Any) {
            listeners(event).forEach { it.call(*args) }
        }
    }
}
