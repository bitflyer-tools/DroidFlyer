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
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.subjects.PublishSubject
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/** A realtime connection owned by a single activity session. */
class RealtimeClient internal constructor(
    val gson: Gson,
    val preference: APITokenPreference,
    private val socketFactory: () -> Socket
) {
    constructor(gson: Gson, preference: APITokenPreference) : this(
        gson,
        preference,
        {
            IO.socket("https://io.lightstream.bitflyer.com", IO.Options().apply {
                transports = arrayOf("websocket")
                forceNew = true
            })
        }
    )

    val board: PublishSubject<Board> = PublishSubject.create<Board>()
    val boardSnapshot: PublishSubject<Board> = PublishSubject.create<Board>()
    val executions: PublishSubject<List<Execution>> = PublishSubject.create<List<Execution>>()
    val childOrderEvents: Observable<List<ChildOrderEvent>>
        get() = deliverPrivate(childEvents)
    val parentOrderEvents: Observable<List<ParentOrderEvent>>
        get() = deliverPrivate(parentEvents)

    internal val orderChanges: Observable<Unit>
        get() = deliverPrivate(
            Observable.merge(childEvents.map { it.first to Unit }, parentEvents.map { it.first to Unit })
                .debounce(1, TimeUnit.SECONDS)
        )

    private val childEvents = PublishSubject.create<Pair<Socket, List<ChildOrderEvent>>>()
    private val parentEvents = PublishSubject.create<Pair<Socket, List<ParentOrderEvent>>>()

    private val publicChannels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )
    private val privateChannels = listOf(
        "child_order_events",
        "parent_order_events"
    )

    @Volatile private var socket: Socket? = socketFactory()
    @Volatile private var authenticatedSocket: Socket? = null

    init {
        connect(checkNotNull(socket))
    }

    fun authenticate() {
        socket?.takeIf { it.connected() }?.let(::authenticate)
    }

    @Synchronized
    fun restartConnection() {
        val previous = socket ?: return
        val next = socketFactory()
        authenticatedSocket = null
        socket = next
        previous.off()
        previous.disconnect()
        connect(next)
    }

    @Synchronized
    fun disconnect() {
        val previous = socket ?: return
        socket = null
        authenticatedSocket = null
        previous.off()
        previous.disconnect()
    }

    private fun connect(connection: Socket) {
        connection.on(Socket.EVENT_CONNECT) {
            if (connection === socket) {
                publicChannels.forEach { channel -> connection.emit("subscribe", channel) }
                authenticate(connection)
            }
        }
        (publicChannels + privateChannels).forEach { channel ->
            connection.on(channel) { events ->
                events.forEach { publishEvent(connection, channel, it) }
            }
        }
        connection.on(Socket.EVENT_DISCONNECT) {
            synchronized(this) {
                if (authenticatedSocket === connection) authenticatedSocket = null
            }
        }
        connection.connect()
    }

    @Synchronized
    private fun authenticate(connection: Socket) {
        val credentials = preference.read()
        if (!credentials.isComplete || connection !== socket) return
        val params = RealtimeAuthParams.create(credentials.key, credentials.secret)
        connection.emit("auth", JSONObject(gson.toJson(params)), Ack { onAuthenticated(connection, it) })
    }

    @Synchronized
    private fun onAuthenticated(connection: Socket, args: Array<out Any?>) {
        if (connection !== socket) return
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error == null) {
            authenticatedSocket = connection
            privateChannels.forEach { channel -> connection.emit("subscribe", channel) }
        } else {
            Log.d("Socket", "Authentication failed: $error")
        }
    }

    private fun <T : Any> deliverPrivate(events: Observable<Pair<Socket, T>>): Observable<T> =
        events.observeOn(AndroidSchedulers.mainThread())
            .filter { (connection, _) -> connection === socket && connection === authenticatedSocket }
            .map { (_, event) -> event }

    private fun publishEvent(connection: Socket, channel: String, event: Any?) {
        if (connection !== socket) return
        if (channel in privateChannels && connection !== authenticatedSocket) return
        when (event) {
            is JSONObject -> publish(connection, channel, event.toString())
            is JSONArray -> publish(connection, channel, event.toString())
        }
    }

    private fun publish(connection: Socket, channel: String, body: String) {
        when (channel) {
            "lightning_board_FX_BTC_JPY" ->
                decodeAndPublish(body, Board::class.java, board::onNext)
            "lightning_board_snapshot_FX_BTC_JPY" ->
                decodeAndPublish(body, Board::class.java, boardSnapshot::onNext)
            "lightning_executions_FX_BTC_JPY" ->
                decodeAndPublish(body, object : TypeToken<List<Execution>>() {}.type, executions::onNext)
            "child_order_events" ->
                decodeAndPublish<List<ChildOrderEvent>>(body, object : TypeToken<List<ChildOrderEvent>>() {}.type) {
                    childEvents.onNext(connection to it)
                }
            "parent_order_events" ->
                decodeAndPublish<List<ParentOrderEvent>>(body, object : TypeToken<List<ParentOrderEvent>>() {}.type) {
                    parentEvents.onNext(connection to it)
                }
        }
    }

    private fun <T : Any> decodeAndPublish(body: String, type: Type, publish: (T) -> Unit) {
        val decoded = try {
            gson.fromJson<T>(body, type)
        } catch (_: JsonParseException) {
            return
        }
        if (decoded != null) publish(decoded)
    }
}
