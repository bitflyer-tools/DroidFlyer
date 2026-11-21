package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.app.Activity
import android.app.Application
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())

    @Test
    fun subscribesToBoardAndExecutionsAndKeepsAuthenticatedSubscriptions() {
        preference.save(APICredentials("key", "secret"))
        val client = RealtimeClient(gson, preference, socket)
        socket.receive(Socket.EVENT_CONNECT)
        assertEquals(
            listOf("lightning_board_FX_BTC_JPY", "lightning_board_snapshot_FX_BTC_JPY", "lightning_executions_FX_BTC_JPY"),
            socket.subscriptions
        )
        assertFalse(socket.hasListeners("lightning_ticker_FX_BTC_JPY"))

        socket.authAck!!.call(JSONObject.NULL)
        assertEquals(listOf("child_order_events", "parent_order_events"), socket.subscriptions.drop(3))

        val board = client.board.test()
        val snapshot = client.boardSnapshot.test()
        val childOrders = client.childOrderEvents.test()
        val parentOrders = client.parentOrderEvents.test()
        val boardPayload = JSONObject("""{"mid_price":123456.0,"bids":[],"asks":[]}""")
        socket.receive("lightning_board_FX_BTC_JPY", boardPayload)
        socket.receive("lightning_board_snapshot_FX_BTC_JPY", boardPayload)
        socket.receive("child_order_events", JSONArray())
        socket.receive("parent_order_events", JSONArray())
        val expectedBoard = Board(123456.0, emptyList(), emptyList())
        board.assertValue(expectedBoard)
        snapshot.assertValue(expectedBoard)
        childOrders.assertValue(emptyList())
        parentOrders.assertValue(emptyList())
    }

    @Test
    fun publishesPrivateExecutionBatchWithFractionalAndIntegralPrices() {
        val client = RealtimeClient(gson, preference, socket)
        val observer = client.childOrderEvents.test()
        val payload = JSONArray(
            """
                [{"product_code":"ETH_BTC","event_type":"EXECUTION","price":0.02744},
                 {"product_code":"BTC_JPY","event_type":"EXECUTION","price":10000000}]
            """.trimIndent()
        )

        socket.receive("child_order_events", payload)

        observer.assertValue { events ->
            events.all { it.isExecution() } &&
                events.map { it.productCode to it.price } ==
                listOf("ETH_BTC" to BigDecimal("0.02744"), "BTC_JPY" to BigDecimal("10000000"))
        }
    }

    @Test
    fun ignoresAuthenticationAcknowledgementAfterDisconnect() {
        preference.save(APICredentials("key", "secret"))
        val client = RealtimeClient(gson, preference, socket)
        socket.receive(Socket.EVENT_CONNECT)
        val acknowledge = checkNotNull(socket.authAck)
        val publicSubscriptions = socket.subscriptions.toList()

        client.disconnect()
        acknowledge.call(JSONObject.NULL)

        assertEquals(publicSubscriptions, socket.subscriptions)
    }

    @Test
    fun valuationsWaitForFirstValidExecutionAfterPrivateDataLoads() {
        val client = RealtimeClient(gson, preference, socket)
        val api = mock<APIClientV1>()
        val position = Position("FX_BTC_JPY", "BUY", 10_000_000L, 0.1, 0.0, 0.0, 0.0, 1L, 0.0)
        whenever(api.getPositions()).thenReturn(Observable.just(listOf(position)))
        val collateral = PublishSubject.create<Map<String, Any>>()
        whenever(api.getCollateral()).thenReturn(collateral)
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())

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
                TradingTotals(null, null, 0L, null),
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
                TradingTotals(null, null, 0L, null),
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
    fun disconnectExpiresHeaderValuationsUntilAFreshExecution() {
        val client = RealtimeClient(gson, preference, socket)
        val api = mock<APIClientV1>()
        val position = Position("FX_BTC_JPY", "BUY", 11_000_000L, 0.1, 0.0, 0.0, 0.0, 1L, 0.0)
        whenever(api.getPositions()).thenReturn(Observable.just(emptyList()), Observable.just(listOf(position)))
        whenever(api.getCollateral()).thenReturn(
            Observable.just(mapOf("collateral" to 500_000.0)),
            Observable.just(mapOf("collateral" to 600_000.0))
        )
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())

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
        val client = RealtimeClient(gson, preference, socket)
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
        val client = RealtimeClient(gson, preference, socket)
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
        val client = RealtimeClient(gson, preference, socket)
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
