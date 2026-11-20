package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class APIClientV1Test {
    @Test
    fun getDepositsUsesVersionedGetEndpoint() {
        val api = apiReturning("/v1/me/getdeposits", "[]")

        assertEquals(emptyList<Map<String, Any>>(), api.getDeposits().blockingSingle())
    }

    @Test
    fun getExecutionPricesRequestsTheProductAndCountAndParsesPrices() {
        val api = apiReturning(
            "/v1/executions?product_code=FX_BTC_JPY&count=500",
            """[{"id":123,"price":9876543.5},{"id":122,"price":9876543}]"""
        )

        api.getExecutionPrices("FX_BTC_JPY", 500).test()
            .assertResult(listOf(ExecutionPrice(123, 9876543.5), ExecutionPrice(122, 9876543.0)))
    }

    @Test
    fun getExecutionPricesRejectsANonNumericPrice() {
        val api = apiReturning(
            "/v1/executions?product_code=FX_BTC_JPY&count=500",
            """[{"id":123,"price":"invalid"}]"""
        )

        api.getExecutionPrices("FX_BTC_JPY", 500).test()
            .assertFailure(NumberFormatException::class.java)
    }

    @Test
    fun getExecutionPricesPropagatesHttpFailure() {
        val api = apiReturning(
            "/v1/executions?product_code=FX_BTC_JPY&count=500",
            """{"error_message":"Service unavailable"}""",
            status = 503
        )

        api.getExecutionPrices("FX_BTC_JPY", 500).test()
            .assertNoValues()
            .assertNotComplete()
            .assertError { it is HttpException && it.code() == 503 }
    }

    private fun apiReturning(target: String, body: String, status: Int = 200): APIClientV1 {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals(target, request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty())
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("")
                .body(body.toResponseBody())
                .build()
        }.build()
        return Retrofit.Builder()
            .baseUrl("https://api.bitflyer.jp/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)
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
