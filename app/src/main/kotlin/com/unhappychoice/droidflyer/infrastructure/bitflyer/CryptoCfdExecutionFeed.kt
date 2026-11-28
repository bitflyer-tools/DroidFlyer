package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import io.reactivex.subjects.PublishSubject
import io.socket.client.Socket
import org.json.JSONArray

internal class CryptoCfdExecutionFeed(private val gson: Gson, private val socket: Socket = newRealtimeSocket()) {
    private val batches = PublishSubject.create<List<Execution>>()
    private val executionsType = object : TypeToken<List<Execution>>() {}.type
    val executions = batches.hide()

    fun connect() {
        socket.on(Socket.EVENT_CONNECT) { socket.emit("subscribe", CRYPTO_CFD_EXECUTIONS_CHANNEL) }
        socket.on(CRYPTO_CFD_EXECUTIONS_CHANNEL) { events ->
            events.filterIsInstance<JSONArray>().forEach { payload ->
                gson.decodeRealtimePayload<List<Execution>>(payload.toString(), executionsType)?.let(batches::onNext)
            }
        }
        socket.connect()
    }

    fun disconnect() {
        socket.off()
        socket.disconnect()
    }
}
