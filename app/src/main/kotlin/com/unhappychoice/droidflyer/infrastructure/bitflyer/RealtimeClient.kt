package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pubnub.api.PNConfiguration
import com.pubnub.api.PubNub
import com.pubnub.api.callbacks.SubscribeCallback
import com.pubnub.api.models.consumer.PNStatus
import com.pubnub.api.models.consumer.pubsub.PNMessageResult
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult
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
class RealtimeClient {
    private val configuration = PNConfiguration().apply {
        subscribeKey = "sub-c-52a9ab50-291b-11e5-baaa-0619f8945a4f"
    }
    private val pubnub = PubNub(configuration)

    val board = PublishSubject.create<Map<String, Any>>()
    val boardSnapshot = PublishSubject.create<Map<String, Any>>()
    val ticker = PublishSubject.create<Map<String, Any>>()
    val executions = PublishSubject.create<Array<Map<String, Any>>>()

    fun connect() {
        pubnub.addListener(object: SubscribeCallback() {
            override fun status(pubnub: PubNub?, status: PNStatus?) {

            }

            override fun message(pubnub: PubNub?, message: PNMessageResult?) {
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                val listType = object : TypeToken<Array<Map<String, Any>>>() {}.type
                when (message?.channel) {
                    "lightning_board_FX_BTC_JPY" -> board.onNext(Gson().fromJson(message.message, mapType))
                    "lightning_board_snapshot_FX_BTC_JPY" -> boardSnapshot.onNext(Gson().fromJson(message.message, mapType))
                    "lightning_ticker_FX_BTC_JPY" -> ticker.onNext(Gson().fromJson(message.message, mapType))
                    "lightning_executions_FX_BTC_JPY" -> executions.onNext(Gson().fromJson(message.message, listType))
                }
            }

            override fun presence(pubnub: PubNub?, presence: PNPresenceEventResult?) {

            }
        })
        val channels = listOf(
            "lightning_board_FX_BTC_JPY",
            "lightning_board_snapshot_FX_BTC_JPY",
            "lightning_ticker_FX_BTC_JPY",
            "lightning_executions_FX_BTC_JPY"
        )
        pubnub.subscribe().channels(channels).execute()
    }

}

