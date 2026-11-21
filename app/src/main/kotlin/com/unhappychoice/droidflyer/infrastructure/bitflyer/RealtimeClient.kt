package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.subjects.PublishSubject
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Type

/** A realtime connection owned by a single activity session. */
class RealtimeClient internal constructor(
    val gson: Gson,
    val preference: APITokenPreference,
    private val socket: Socket
) {
    constructor(gson: Gson, preference: APITokenPreference) : this(
        gson,
        preference,
        IO.socket("https://io.lightstream.bitflyer.com", IO.Options().apply {
            transports = arrayOf("websocket")
            forceNew = true
        })
    )

    val board: PublishSubject<Board> = PublishSubject.create<Board>()
    val boardSnapshot: PublishSubject<Board> = PublishSubject.create<Board>()
    val executions: PublishSubject<List<Execution>> = PublishSubject.create<List<Execution>>()
    val childOrderEvents: PublishSubject<List<ChildOrderEvent>> = PublishSubject.create<List<ChildOrderEvent>>()
    val parentOrderEvents: PublishSubject<List<ParentOrderEvent>> = PublishSubject.create<List<ParentOrderEvent>>()

    val privateChannelsReady = PublishSubject.create<Unit>()
    internal val privateChannelsDisconnected = PublishSubject.create<Unit>()
    internal val privateChannelAuthenticationFailed = PublishSubject.create<Unit>()

    private val publicChannels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )
    private val privateChannels = listOf(
        "child_order_events",
        "parent_order_events"
    )

    @Volatile private var disconnected = false

    init {
        socket.on(Socket.EVENT_CONNECT) {
            publicChannels.forEach { channel -> socket.emit("subscribe", channel) }
            authenticate()
        }

        (publicChannels + privateChannels).forEach { channel ->
            socket.on(channel) { events -> events.forEach { publishEvent(channel, it) } }
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            privateChannelsDisconnected.onNext(Unit)
            Log.d("Socket", "Disconnected")
        }

        socket.connect()
    }

    fun authenticate() {
        if (disconnected) return
        val credentials = preference.read()
        if (credentials.key.isEmpty() || credentials.secret.isEmpty()) return
        val params = RealtimeAuthParams.create(credentials.key, credentials.secret)
        socket.emit("auth", JSONObject(gson.toJson(params)), Ack { onAuthenticated(it) })
    }

    fun disconnect() {
        if (disconnected) return
        disconnected = true
        socket.off()
        socket.disconnect()
    }

    private fun onAuthenticated(args: Array<out Any?>) {
        if (disconnected) return
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error == null) {
            subscribePrivateChannels()
            privateChannelsReady.onNext(Unit)
        } else {
            privateChannelAuthenticationFailed.onNext(Unit)
            Log.d("Socket", "Authentication failed: $error")
        }
    }

    private fun subscribePrivateChannels() =
        privateChannels.forEach { channel -> socket.emit("subscribe", channel) }

    private fun publishEvent(channel: String, event: Any?) {
        if (disconnected) return
        when (event) {
            is JSONObject -> publish(channel, event.toString())
            is JSONArray -> publish(channel, event.toString())
        }
    }

    private fun publish(channel: String, body: String) {
        when (channel) {
            "lightning_board_FX_BTC_JPY" ->
                decodeAndPublish(body, Board::class.java, board)
            "lightning_board_snapshot_FX_BTC_JPY" ->
                decodeAndPublish(body, Board::class.java, boardSnapshot)
            "lightning_executions_FX_BTC_JPY" ->
                decodeAndPublish(body, object : TypeToken<List<Execution>>() {}.type, executions)
            "child_order_events" ->
                decodeAndPublish(body, object : TypeToken<List<ChildOrderEvent>>() {}.type, childOrderEvents)
            "parent_order_events" ->
                decodeAndPublish(body, object : TypeToken<List<ParentOrderEvent>>() {}.type, parentOrderEvents)
        }
    }

    private fun <T : Any> decodeAndPublish(body: String, type: Type, subject: PublishSubject<T>) {
        val decoded = try {
            gson.fromJson<T>(body, type)
        } catch (_: JsonParseException) {
            return
        }
        if (decoded != null) subject.onNext(decoded)
    }
}
