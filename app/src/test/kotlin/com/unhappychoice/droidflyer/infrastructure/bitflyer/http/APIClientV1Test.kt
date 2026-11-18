package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
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

    @Test
    fun getMyExecutionsUsesPrivateCfdEndpointAndDecodesFills() {
        val cursors = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("/v1/me/getexecutions", request.url.encodedPath)
            assertEquals("FX_BTC_JPY", request.url.queryParameter("product_code"))
            assertEquals("50", request.url.queryParameter("count"))
            cursors.add(request.url.queryParameter("before"))
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""
                    [{"id":4294967297,"side":"SELL","price":33470.5,"size":0.00000001,
                      "exec_date":"2015-07-07T09:57:40.3971234"}]
                """.trimIndent().toResponseBody())
                .build()
        }.build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.bitflyer.jp/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)

        val fill = PrivateExecution(
            4294967297L, "SELL", "33470.5".toBigDecimal(), "0.00000001".toBigDecimal(),
            "2015-07-07T09:57:40.3971234"
        )
        assertEquals(listOf(fill), api.getMyExecutions(count = 50).blockingSingle())
        assertEquals(listOf(fill), api.getMyExecutions(count = 50, before = 4294967298L).blockingSingle())
        assertEquals(listOf(null, "4294967298"), cursors)
    }
}
