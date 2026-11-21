package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
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
    fun getChildOrdersSendsExplicitCountForActiveOrders() {
        val api = apiReturning(
            "/v1/me/getchildorders?product_code=FX_BTC_JPY&child_order_state=ACTIVE&count=500",
            "[]"
        )

        api.getChildOrders("FX_BTC_JPY", count = 500).test().assertResult(emptyList())
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
}
