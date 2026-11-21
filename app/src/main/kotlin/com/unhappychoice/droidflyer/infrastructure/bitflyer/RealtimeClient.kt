package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.subjects.PublishSubject
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject

/** A realtime connection owned by a single activity session. */
class RealtimeClient internal constructor(
    val gson: Gson,
    val preference: APITokenPreference,
    private val socket: Socket,
    private val createPrivateSocket: () -> Socket
) {
    constructor(gson: Gson, preference: APITokenPreference) : this(
        gson,
        preference,
        newSocket(),
        ::newSocket
    )

    val board: PublishSubject<Board> = PublishSubject.create<Board>()
    val boardSnapshot: PublishSubject<Board> = PublishSubject.create<Board>()
    val executions: PublishSubject<List<Execution>> = PublishSubject.create<List<Execution>>()
    private val childEvents = PublishSubject.create<PrivateEvent<List<ChildOrderEvent>>>()
    private val parentEvents = PublishSubject.create<PrivateEvent<List<ParentOrderEvent>>>()
    internal val childOrderEvents = childEvents.hide()
    internal val parentOrderEvents = parentEvents.hide()

    private val publicChannels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )
    private val accountLock = Any()
    private var account: PrivateRealtimeConnection? = null
    @Volatile private var disconnected = false

    init {
        socket.on(Socket.EVENT_CONNECT) {
            publicChannels.forEach { channel -> socket.emit("subscribe", channel) }
        }
        publicChannels.forEach { channel ->
            socket.on(channel) { events -> events.forEach { publishEvent(channel, it) } }
        }
        socket.connect()
        updateAccount {}
    }

    internal fun updateAccount(save: () -> Unit) = synchronized(accountLock) {
        save()
        if (!disconnected) connectSavedAccount()
    }

    fun disconnect(): Unit = synchronized(accountLock) {
        if (disconnected) return@synchronized
        disconnected = true
        closeAccount()
        socket.off()
        socket.disconnect()
    }

    private fun connectSavedAccount() {
        val credentials = preference.read()
        if (account?.canReuse(credentials) == true) return

        closeAccount()
        if (credentials.key.isEmpty() || credentials.secret.isEmpty()) return
        // Private channel messages carry no account identity, so each account needs its own socket.
        val connection = PrivateRealtimeConnection(gson, credentials, createPrivateSocket(), accountLock)
        account = connection
        connection.connect(childEvents::onNext, parentEvents::onNext)
    }

    private fun closeAccount() {
        account?.close()
        account = null
    }

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
                board.onNext(gson.fromJson(body, Board::class.java))
            "lightning_board_snapshot_FX_BTC_JPY" ->
                boardSnapshot.onNext(gson.fromJson(body, Board::class.java))
            "lightning_executions_FX_BTC_JPY" ->
                executions.onNext(gson.fromJson(body, object : TypeToken<List<Execution>>() {}.type))
        }
    }

    private companion object {
        fun newSocket(): Socket = IO.socket("https://io.lightstream.bitflyer.com", IO.Options().apply {
            transports = arrayOf("websocket")
            forceNew = true
        })
    }
}
