package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pubnub.api.PNConfiguration
import com.pubnub.api.PubNub
import com.pubnub.api.callbacks.SubscribeCallback
import com.pubnub.api.models.consumer.PNStatus
import com.pubnub.api.models.consumer.pubsub.PNMessageResult
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Ticker
import io.reactivex.subjects.PublishSubject

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

    private val configuration = PNConfiguration().apply { subscribeKey = "sub-c-52a9ab50-291b-11e5-baaa-0619f8945a4f" }
    private val pubnub = PubNub(configuration)
    private val channels = listOf(
        "lightning_board_FX_BTC_JPY",
        "lightning_board_snapshot_FX_BTC_JPY",
        "lightning_ticker_FX_BTC_JPY",
        "lightning_executions_FX_BTC_JPY"
    )

    fun connect() {
        pubnub.addListener(object: SubscribeCallback() {
            override fun status(pubnub: PubNub?, status: PNStatus?) { }
            override fun message(pubnub: PubNub?, message: PNMessageResult?) { publish(message) }
            override fun presence(pubnub: PubNub?, presence: PNPresenceEventResult?) { }
        })
        pubnub.subscribe().channels(channels).execute()
    }

    private fun publish(message: PNMessageResult?) {
        when (message?.channel) {
            "lightning_board_FX_BTC_JPY" ->
                board.onNext(gson.fromJson(message.message, Board::class.java))
            "lightning_board_snapshot_FX_BTC_JPY" ->
                boardSnapshot.onNext(gson.fromJson(message.message, Board::class.java))
            "lightning_ticker_FX_BTC_JPY" ->
                ticker.onNext(gson.fromJson(message.message, Ticker::class.java))
            "lightning_executions_FX_BTC_JPY" ->
                executions.onNext(gson.fromJson(message.message, object : TypeToken<List<Execution>>(){}.type))
        }
    }
}
