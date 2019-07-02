package com.unhappychoice.droidflyer.infrastructure.bitflyer

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Ticker
import io.reactivex.subjects.PublishSubject
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
 * ```
 */
class RealtimeClient(val gson: Gson) {
    val board: PublishSubject<Board> = PublishSubject.create<Board>()
    val boardSnapshot: PublishSubject<Board> = PublishSubject.create<Board>()
    val ticker: PublishSubject<Ticker> = PublishSubject.create<Ticker>()
    val executions: PublishSubject<List<Execution>> = PublishSubject.create<List<Execution>>()

    private val host = "https://io.lightstream.bitflyer.com"
    private val channels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_ticker_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )

    init {
        val options = IO.Options().apply { transports = arrayOf("websocket") }
        val socket = IO.socket(host, options)

        socket.on(Socket.EVENT_CONNECT) {
            channels.forEach { channel -> socket.emit("subscribe", channel) }
        }

        channels.forEach { channel ->
            socket.on(channel) { events ->
                events
                    .mapNotNull { it as? JSONObject }
                    .forEach { publish(channel, it.toString(4)) }
                events
                    .mapNotNull { (it as? JSONArray) }
                    .forEach { publish(channel, it.toString(4)) }
            }
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            Log.d("Socket", "Disconnected")
        }

        socket.connect()
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
        }
    }
}
