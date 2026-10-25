package com.unhappychoice.droidflyer.infrastructure.bitflyer

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.winterbe.expekt.should
import org.junit.Test

class RealtimeAuthParamsTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()

    @Test
    fun createSignsTimestampAndNonce() {
        val params = RealtimeAuthParams.create("key", "secret", 1500000000000L, "0123456789abcdef")
        params.signature.should.equal("15000000000000123456789abcdef".toHmacSHA256("secret"))
    }

    @Test
    fun createGeneratesLongEnoughNonce() {
        val params = RealtimeAuthParams.create("key", "secret")
        params.nonce.length.should.be.at.least(16)
    }

    @Test
    fun serializesWithSnakeCaseKeys() {
        val json = gson.toJsonTree(RealtimeAuthParams("key", 1L, "nonce", "sign")) as JsonObject
        json.keySet().should.equal(setOf("api_key", "timestamp", "nonce", "signature"))
    }
}
