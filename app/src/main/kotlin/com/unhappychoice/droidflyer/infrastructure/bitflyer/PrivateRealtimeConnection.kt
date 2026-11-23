package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.socket.client.AckWithTimeout
import io.socket.client.Socket
import io.socket.thread.EventThread
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class PrivateEvent<T>(val value: T, val isCurrent: () -> Boolean)

internal class PrivateRealtimeConnection(
    private val gson: Gson,
    private val credentials: APICredentials,
    private val socket: Socket,
    private val lock: Any
) {
    private var closed = false
    private var attempt: Any? = null
    private var retry: Disposable? = null
    @Volatile private var authenticated: Any? = null

    fun canReuse(other: APICredentials): Boolean =
        authenticated != null && credentials.key == other.key && credentials.secret == other.secret

    fun connect(
        childOrders: (PrivateEvent<List<ChildOrderEvent>>) -> Unit,
        parentOrders: (PrivateEvent<List<ParentOrderEvent>>) -> Unit
    ) {
        listen("child_order_events", object : TypeToken<List<ChildOrderEvent>>() {}, childOrders)
        listen("parent_order_events", object : TypeToken<List<ParentOrderEvent>>() {}, parentOrders)
        socket.on(Socket.EVENT_CONNECT) {
            synchronized(lock) {
                invalidate()
                authenticate(INITIAL_RETRY_SECONDS)
            }
        }
        socket.on(Socket.EVENT_DISCONNECT) { synchronized(lock) { invalidate() } }
        socket.connect()
    }

    fun close() {
        closed = true
        invalidate()
        socket.off()
        socket.disconnect()
    }

    private fun authenticate(retrySeconds: Long) {
        if (closed) return
        val request = Any()
        attempt = request
        val params = RealtimeAuthParams.create(credentials.key, credentials.secret)
        socket.emit("auth", JSONObject(gson.toJson(params)), object : AckWithTimeout(AUTH_TIMEOUT_MILLIS) {
            override fun onSuccess(vararg args: Any?) {
                synchronized(lock) { onAuthenticated(request, args) }
            }

            override fun onTimeout() {
                cancelTimer()
                synchronized(lock) { scheduleRetry(request, retrySeconds) }
            }
        })
    }

    private fun scheduleRetry(request: Any, delaySeconds: Long) {
        if (attempt !== request) return
        attempt = null
        if (!socket.connected()) return
        val retryToken = Any()
        attempt = retryToken
        retry = Schedulers.computation().scheduleDirect({
            EventThread.exec {
                synchronized(lock) {
                    if (attempt === retryToken && socket.connected()) {
                        retry = null
                        authenticate((delaySeconds * 2).coerceAtMost(MAX_RETRY_SECONDS))
                    }
                }
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun onAuthenticated(request: Any, args: Array<out Any?>) {
        if (attempt !== request) return
        attempt = null
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error != null) {
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
        retry?.dispose()
        retry = null
    }

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_RETRY_SECONDS = 1L
        const val MAX_RETRY_SECONDS = 30L
    }
}
