package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.socket.client.Ack
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

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
    fun executionUpdatesTradingPriceAfterEmptyBatch() {
        val client = RealtimeClient(gson, preference, socket)
        val api = mock<APIClientV1>()
        whenever(api.getPositions()).thenReturn(Observable.empty())
        whenever(api.getCollateral()).thenReturn(Observable.empty())
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())

        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        try {
            val service = CurrentStatusService(api, client, api)
            socket.receive(Socket.EVENT_CONNECT)
            socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":123456.0}]"""))
            assertEquals(123456.0, service.currentPrice.value, 0.0)

            socket.receive("lightning_executions_FX_BTC_JPY", JSONArray())
            assertEquals(123456.0, service.currentPrice.value, 0.0)

            socket.receive("lightning_executions_FX_BTC_JPY", JSONArray("""[{"price":234567.0}]"""))
            assertEquals(234567.0, service.currentPrice.value, 0.0)
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
