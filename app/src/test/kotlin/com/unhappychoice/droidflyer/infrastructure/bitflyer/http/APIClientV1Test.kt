package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import java.math.BigDecimal
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
    fun getPositionsRequestsCryptoCfdByDefaultAndDecodesPositionFields() {
        val api = apiReturning(
            "/v1/me/getpositions?product_code=FX_BTC_JPY",
            """
                [{"product_code":"FX_BTC_JPY","side":"SELL","price":9876543,"size":0.25,
                  "commission":1.5,"swap_point_accumulate":-2.5,"require_collateral":1500.5,
                  "leverage":2,"pnl":-100.5}]
            """.trimIndent()
        )

        val expected = Position(
            productCode = "FX_BTC_JPY",
            side = "SELL",
            price = 9876543,
            size = 0.25,
            commission = 1.5,
            swapPointAccumulate = -2.5,
            requireCollateral = 1500.5,
            leverage = 2,
            pnl = -100.5
        )
        api.getPositions().test().assertResult(listOf(expected))
    }

    @Test
    fun getPositionsRejectsANonNumericSize() {
        val api = apiReturning(
            "/v1/me/getpositions?product_code=FX_BTC_JPY",
            """[{"product_code":"FX_BTC_JPY","side":"SELL","price":9876543,"size":"invalid"}]"""
        )

        api.getPositions().test().assertFailure(NumberFormatException::class.java)
    }

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
    fun getExecutionPricesRequestsTheCursorAndRetainsTimestamps() {
        val api = apiReturning(
            "/v1/executions?product_code=FX_BTC_JPY&count=500&before=124",
            """[{"id":123,"price":9876543.5,"exec_date":"2025-01-01T00:01:02.1234567"}]"""
        )

        api.getExecutionPrices("FX_BTC_JPY", 500, before = 124).test()
            .assertResult(listOf(ExecutionPrice(123, 9876543.5, "2025-01-01T00:01:02.1234567")))
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

    @Test
    fun getFundingRateRequestsCryptoCfdAndParsesAnExplicitZero() {
        val api = apiReturning(
            "/v1/getfundingrate?product_code=FX_BTC_JPY",
            """{"current_funding_rate":0.000000000000,"next_funding_rate_settledate":"2024-04-15T13:00:00"}"""
        )

        api.getFundingRate("FX_BTC_JPY").test()
            .assertResult(FundingRate(BigDecimal("0.000000000000"), "2024-04-15T13:00:00"))
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
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        return Retrofit.Builder()
            .baseUrl("https://api.bitflyer.jp/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)
    }
}
