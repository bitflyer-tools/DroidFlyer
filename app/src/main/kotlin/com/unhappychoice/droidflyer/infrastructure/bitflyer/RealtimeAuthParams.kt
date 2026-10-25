package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.unhappychoice.droidflyer.extension.toHmacSHA256
import java.util.UUID

data class RealtimeAuthParams(
    val apiKey: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String
) {
    companion object {
        fun create(
            key: String,
            secret: String,
            timestamp: Long = System.currentTimeMillis(),
            nonce: String = UUID.randomUUID().toString().replace("-", "")
        ): RealtimeAuthParams =
            RealtimeAuthParams(key, timestamp, nonce, "$timestamp$nonce".toHmacSHA256(secret))
    }
}
