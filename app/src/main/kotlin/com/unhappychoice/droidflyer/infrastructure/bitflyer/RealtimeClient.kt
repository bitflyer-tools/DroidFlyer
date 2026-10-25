package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Ticker
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.subjects.PublishSubject
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject

/**
 * ```
 * val client = RealtimeClient()
 * client.connect()
 * client.board.subscribeNext { Log.d("a", it.toString()) }
 * client.boardSnapshot.subscribeNext { Log.d("a", it.toString()) }
 * client.executions.subscribeNext { Log.d("a", it.toString()) }
 * client.ticker.subscribeNext { Log.d("a", it.toString()) }
 * client.childOrderEvents.subscribeNext { Log.d("a", it.toString()) }
 * client.parentOrderEvents.subscribeNext { Log.d("a", it.toString()) }
 * ```
 */
class RealtimeClient(val gson: Gson, val preference: APITokenPreference) {
    val board: PublishSubject<Board> = PublishSubject.create<Board>()
    val boardSnapshot: PublishSubject<Board> = PublishSubject.create<Board>()
    val ticker: PublishSubject<Ticker> = PublishSubject.create<Ticker>()
    val executions: PublishSubject<List<Execution>> = PublishSubject.create<List<Execution>>()
    val childOrderEvents: PublishSubject<List<ChildOrderEvent>> = PublishSubject.create<List<ChildOrderEvent>>()
    val parentOrderEvents: PublishSubject<List<ParentOrderEvent>> = PublishSubject.create<List<ParentOrderEvent>>()

    private val host = "https://io.lightstream.bitflyer.com"
    private val publicChannels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_ticker_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )
    private val privateChannels = listOf(
        "child_order_events",
        "parent_order_events"
    )

    private val socket: Socket = IO.socket(host, IO.Options().apply { transports = arrayOf("websocket") })

    init {
        socket.on(Socket.EVENT_CONNECT) {
            publicChannels.forEach { channel -> socket.emit("subscribe", channel) }
            authenticate()
        }

        (publicChannels + privateChannels).forEach { channel ->
            socket.on(channel) { events -> events.forEach { publishEvent(channel, it) } }
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            Log.d("Socket", "Disconnected")
        }

        socket.connect()
    }

    fun authenticate() {
        if (preference.key == "" || preference.secret == "") return
        val params = RealtimeAuthParams.create(preference.key, preference.secret)
        socket.emit("auth", JSONObject(gson.toJson(params)), Ack { onAuthenticated(it) })
    }

    private fun onAuthenticated(args: Array<out Any?>) {
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error == null) subscribePrivateChannels() else Log.d("Socket", "Authentication failed: $error")
    }

    private fun subscribePrivateChannels() =
        privateChannels.forEach { channel -> socket.emit("subscribe", channel) }

    private fun publishEvent(channel: String, event: Any?) {
        when (event) {
            is JSONObject -> publish(channel, event.toString(4))
            is JSONArray -> publish(channel, event.toString(4))
        }
    }

    private fun publish(channel: String, body: String) {
        when (channel) {
            "lightning_board_FX_BTC_JPY" ->
                board.onNext(gson.fromJson(body, Board::class.java))
            "lightning_board_snapshot_FX_BTC_JPY" ->
                boardSnapshot.onNext(gson.fromJson(body, Board::class.java))
            "lightning_ticker_FX_BTC_JPY" ->
                ticker.onNext(gson.fromJson(body, Ticker::class.java))
            "lightning_executions_FX_BTC_JPY" ->
                executions.onNext(gson.fromJson(body, object : TypeToken<List<Execution>>() {}.type))
            "child_order_events" ->
                childOrderEvents.onNext(gson.fromJson(body, object : TypeToken<List<ChildOrderEvent>>() {}.type))
            "parent_order_events" ->
                parentOrderEvents.onNext(gson.fromJson(body, object : TypeToken<List<ParentOrderEvent>>() {}.type))
        }
    }
}
