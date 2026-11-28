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
    private var subscriptionRejected = false
    private var retry: Disposable? = null
    @Volatile private var authenticated: Any? = null

    @Volatile private var ready: Any? = null
    private lateinit var availability: (PrivateEvent<Boolean>) -> Unit

    fun canReuse(other: APICredentials): Boolean =
        authenticated != null && !subscriptionRejected && matches(other)

    fun matches(other: APICredentials): Boolean =
        credentials.key == other.key && credentials.secret == other.secret

    fun connect(
        childOrders: (PrivateEvent<List<ChildOrderEvent>>) -> Unit,
        parentOrders: (PrivateEvent<List<ParentOrderEvent>>) -> Unit,
        availability: (PrivateEvent<Boolean>) -> Unit
    ) {
        this.availability = availability
        listen("child_order_events", object : TypeToken<List<ChildOrderEvent>>() {}, childOrders)
        listen("parent_order_events", object : TypeToken<List<ParentOrderEvent>>() {}, parentOrders)
        socket.on(Socket.EVENT_CONNECT) {
            synchronized(lock) {
                if (authenticated != null || attempt != null) invalidate()
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
        socket.emit("auth", JSONObject(gson.toJson(params)), object : AckWithTimeout(ACK_TIMEOUT_MILLIS) {
            override fun onSuccess(vararg args: Any?) {
                synchronized(lock) { onAuthenticated(request, args, retrySeconds) }
            }

            override fun onTimeout() {
                cancelTimer()
                synchronized(lock) {
                    if (attempt === request) scheduleRetry(retrySeconds)
                }
            }
        })
    }

    private fun scheduleRetry(delaySeconds: Long) {
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

    private fun onAuthenticated(request: Any, args: Array<out Any?>, retrySeconds: Long) {
        if (attempt !== request) return
        attempt = null
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error != null) {
            availability(PrivateEvent(false) { true })
            Log.d("Socket", "Authentication failed: $error")
            return
        }
        authenticated = request
        val pending = mutableSetOf("child_order_events", "parent_order_events")
        pending.toList().forEach { channel ->
            socket.emit("subscribe", channel, object : AckWithTimeout(ACK_TIMEOUT_MILLIS) {
                override fun onSuccess(vararg args: Any?) {
                    synchronized(lock) { onSubscribed(request, channel, pending, args) }
                }

                override fun onTimeout() {
                    cancelTimer()
                    synchronized(lock) {
                        if (authenticated === request && channel in pending) retrySubscriptions(retrySeconds)
                    }
                }
            })
        }
    }

    private fun retrySubscriptions(retrySeconds: Long) {
        authenticated = null
        scheduleRetry(retrySeconds)
        availability(PrivateEvent(false) { true })
    }

    private fun onSubscribed(request: Any, channel: String, pending: MutableSet<String>, args: Array<out Any?>) {
        if (authenticated !== request || channel !in pending) return
        val error = args.firstOrNull()?.takeUnless { it == JSONObject.NULL }
        if (error != null) {
            pending.clear()
            subscriptionRejected = true
            availability(PrivateEvent(false) { true })
            Log.d("Socket", "Subscription failed for $channel: $error")
        } else if (pending.remove(channel) && pending.isEmpty()) {
            ready = request
            availability(PrivateEvent(true) { ready === request })
        }
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
        subscriptionRejected = false
        attempt = null
        authenticated = null
        retry?.dispose()
        retry = null
        ready = null
        availability(PrivateEvent(false) { true })
    }

    private companion object {
        const val ACK_TIMEOUT_MILLIS = 5_000L
        const val INITIAL_RETRY_SECONDS = 1L
        const val MAX_RETRY_SECONDS = 30L
    }
}
