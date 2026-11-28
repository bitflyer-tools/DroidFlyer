package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import io.reactivex.subjects.PublishSubject
import io.socket.client.Socket
import org.json.JSONArray

internal class CryptoCfdExecutionFeed(private val gson: Gson, private val socket: Socket = newRealtimeSocket()) {
    sealed interface Event {
        data class Executions(val batch: List<Execution>) : Event
        data object Disconnected : Event
    }

    private val updates = PublishSubject.create<Event>().toSerialized()
    private val executionsType = object : TypeToken<List<Execution>>() {}.type
    val events = updates.hide()

    fun connect() {
        socket.on(Socket.EVENT_CONNECT) { socket.emit("subscribe", CRYPTO_CFD_EXECUTIONS_CHANNEL) }
        socket.on(Socket.EVENT_DISCONNECT) { updates.onNext(Event.Disconnected) }
        socket.on(CRYPTO_CFD_EXECUTIONS_CHANNEL) { payloads ->
            payloads.filterIsInstance<JSONArray>().forEach { payload ->
                gson.decodeRealtimePayload<List<Execution>>(payload.toString(), executionsType)
                    ?.let { batch -> updates.onNext(Event.Executions(batch)) }
            }
        }
        socket.connect()
    }

    fun disconnect() {
        socket.off()
        socket.disconnect()
    }
}
