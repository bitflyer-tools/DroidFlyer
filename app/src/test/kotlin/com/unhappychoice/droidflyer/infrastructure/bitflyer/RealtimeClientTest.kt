package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.app.Application
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.TradingTotals
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RealtimeClientTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val socket = RecordingSocket()
    private val preference = APITokenPreference(RuntimeEnvironment.getApplication())

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
    fun valuationsWaitForFirstValidExecutionAfterPrivateDataLoads() {
        val client = RealtimeClient(gson, preference, socket)
        val api = mock<APIClientV1>()
        val position = Position("FX_BTC_JPY", "BUY", 10_000_000L, 0.1, 0.0, 0.0, 0.0, 1L, 0.0)
        whenever(api.getPositions()).thenReturn(Observable.just(listOf(position)))
        val collateral = PublishSubject.create<Map<String, Any>>()
        whenever(api.getCollateral()).thenReturn(collateral)
        whenever(api.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(api.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())

        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        try {
            val service = CurrentStatusService(api, client, api)
            socket.receive(Socket.EVENT_CONNECT)
            val totals = service.tradingTotals.test()
            assertEquals(listOf(position), service.position.value)
            assertNull(service.currentPrice.value.yen)
            collateral.onNext(mapOf("collateral" to 200_000.0))
            totals.assertValues(
                TradingTotals(null, null, 0L),
                TradingTotals(null, null, 200_000L)
            )

            val channel = "lightning_executions_FX_BTC_JPY"
            socket.receive(channel, JSONArray())
            socket.receive(channel, JSONArray("""[{"price":0.0},{"price":-1.0}]"""))
            assertNull(service.currentPrice.value.yen)
            totals.assertValueCount(2)

            socket.receive(channel, JSONArray("""[{"price":0.0},{"price":10100000.0}]"""))
            assertEquals(10_100_000.0, service.currentPrice.value.yen!!, 0.0)
            totals.assertValues(
                TradingTotals(null, null, 0L),
                TradingTotals(null, null, 200_000L),
                TradingTotals(10_000L, 100_000L, 210_000L)
            ).assertNoErrors()
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    private class RecordingSocket : Socket(Manager(), "/", Manager.Options()) {
        val subscriptions = mutableListOf<String>()
        var authAck: Ack? = null

        override fun connect(): Socket = this

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
