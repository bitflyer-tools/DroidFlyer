package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import io.socket.client.Ack
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject

internal class PrivateEvent<T>(val value: T, val isCurrent: () -> Boolean)

internal class PrivateRealtimeConnection(
    private val gson: Gson,
    private val credentials: APICredentials,
    private val socket: Socket,
    private val lock: Any
) {
    private var closed = false
    private var rejected = false
    private var attempt: Any? = null
    @Volatile private var authenticated: Any? = null

    fun canReuse(other: APICredentials): Boolean =
        !rejected && credentials.key == other.key && credentials.secret == other.secret

    fun connect(
        childOrders: (PrivateEvent<List<ChildOrderEvent>>) -> Unit,
        parentOrders: (PrivateEvent<List<ParentOrderEvent>>) -> Unit
    ) {
        listen("child_order_events", object : TypeToken<List<ChildOrderEvent>>() {}, childOrders)
        listen("parent_order_events", object : TypeToken<List<ParentOrderEvent>>() {}, parentOrders)
        socket.on(Socket.EVENT_CONNECT) { synchronized(lock) { authenticate() } }
        socket.on(Socket.EVENT_DISCONNECT) { synchronized(lock) { invalidate() } }
        socket.connect()
    }

    fun close() {
        closed = true
        invalidate()
        socket.off()
        socket.disconnect()
    }

    private fun authenticate() {
        if (closed) return
        rejected = false
        val request = Any()
        authenticated = null
        attempt = request
        val params = RealtimeAuthParams.create(credentials.key, credentials.secret)
        socket.emit("auth", JSONObject(gson.toJson(params)), Ack { args ->
            synchronized(lock) { onAuthenticated(request, args) }
        })
    }

    private fun onAuthenticated(request: Any, args: Array<out Any?>) {
        if (attempt !== request) return
        attempt = null
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error != null) {
            rejected = true
            Log.d("Socket", "Authentication failed: $error")
            return
        }
        authenticated = request
        socket.emit("subscribe", "child_order_events")
        socket.emit("subscribe", "parent_order_events")
    }

    private fun <T : Any> listen(channel: String, type: TypeToken<T>, publish: (PrivateEvent<T>) -> Unit) {
        socket.on(channel) { events ->
            synchronized(lock) {
                val session = authenticated ?: return@synchronized
                events.filter { it is JSONObject || it is JSONArray }.forEach {
                    gson.decodeRealtimePayload<T>(it.toString(), type.type)?.let { value ->
                        publish(PrivateEvent(value) { authenticated === session })
                    }
                }
            }
        }
    }

    private fun invalidate() {
        attempt = null
        authenticated = null
    }
}
