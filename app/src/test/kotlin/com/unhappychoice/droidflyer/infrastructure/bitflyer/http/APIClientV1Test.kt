package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class APIClientV1Test {
    @Test
    fun getDepositsUsesVersionedGetEndpoint() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("/v1/me/getdeposits", request.url.encodedPath)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("[]".toResponseBody())
                .build()
        }.build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.bitflyer.jp/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)

        assertEquals(emptyList<Map<String, Any>>(), api.getDeposits().blockingSingle())
    }
}
