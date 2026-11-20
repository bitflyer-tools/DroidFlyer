package com.unhappychoice.droidflyer.domain.service

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class StopOrderServiceTest {
    private val requests = mutableListOf<Request>()
    private var parentState = "ACTIVE"
    private var executedSize = 0.0
    private var childOrders = "[]"
    private var cancelStatus = 200

    @Test
    fun validatesStopInput() {
        assertNull(StopOrderRequest.fromInput(StopSide.BUY, "", "5000000"))
        assertNull(StopOrderRequest.fromInput(StopSide.SELL, "0", "5000000"))
        assertNull(StopOrderRequest.fromInput(StopSide.SELL, "NaN", "5000000"))
        assertNull(StopOrderRequest.fromInput(StopSide.SELL, "1e999", "5000000"))
        assertNull(StopOrderRequest.fromInput(StopSide.BUY, "0.01", "-1"))
        assertNull(StopOrderRequest.fromInput(StopSide.BUY, "0.01", "5000000.5"))
        assertEquals("0.01", stopRequest().size.toPlainString())
    }

    @Test
    fun submitsSimpleStopAndReadsTriggerFromParentDetails() {
        val service = service()
        val received = mutableListOf<String>()
        assertTrue(service.submit(service.confirmation(stopRequest())) { received += it.getOrThrow() })
        assertEquals(listOf("acceptance-1"), received)
        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("/v1/me/sendparentorder", request.url.encodedPath)
        assertJsonBody(request, """
            {"order_method":"SIMPLE","parameters":[{"product_code":"FX_BTC_JPY",
             "condition_type":"STOP","side":"SELL","size":0.01,"trigger_price":5000000}]}
        """.trimIndent())

        val order = service.orders.value.single()
        assertEquals(5000000L, order.triggerPrice)
        assertEquals(StopOrderStatus.WAITING, order.status)
        assertTrue(order.canCancel)
        assertTrue(requests.any { it.url.queryParameter("parent_order_state") == "ACTIVE" })
    }

    @Test
    fun parentEventDistinguishesTriggerFromPartialAndFullExecutionAfterReload() {
        val service = service()
        service.refresh()
        service.onEvents(listOf(triggerEvent().copy(productCode = "BTC_JPY")))
        assertEquals(StopOrderStatus.WAITING, service.orders.value.single().status)

        service.onEvents(listOf(triggerEvent()))
        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        assertFalse(service.orders.value.single().canCancel)

        executedSize = 0.005
        service.refreshIfEnabled()
        assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, service.orders.value.single().status)

        parentState = "COMPLETED"
        executedSize = 0.01
        service.refreshIfEnabled()
        assertEquals(StopOrderStatus.EXECUTED, service.orders.value.single().status)
    }

    @Test
    fun reloadRecoversTriggerWithoutRealtimeEvent() {
        childOrders = """[{"child_order_type":"MARKET","executed_size":0}]"""
        val service = service()

        service.refresh()

        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        val childRequest = requests.first { it.url.encodedPath == "/v1/me/getchildorders" }
        assertEquals("parent-1", childRequest.url.queryParameter("parent_order_id"))
        assertEquals("FX_BTC_JPY", childRequest.url.queryParameter("product_code"))
    }

    @Test
    fun cancelsByParentAcceptanceIdAndAllowsRetryAfterFailure() {
        val service = service()
        service.refresh()
        val order = service.orders.value.single()
        var failures = 0
        cancelStatus = 400
        service.cancel(order) { failures++ }
        assertEquals(1, failures)
        assertTrue(service.orders.value.single().canCancel)

        cancelStatus = 200
        service.cancel(order) { fail("Cancellation failed") }
        val cancellations = requests.filter { it.url.encodedPath == "/v1/me/cancelparentorder" }
        assertEquals(2, cancellations.size)
        cancellations.forEach {
            assertEquals("POST", it.method)
            assertJsonBody(it, """{"parent_order_acceptance_id":"acceptance-1","product_code":"FX_BTC_JPY"}""")
        }
        assertEquals(StopOrderStatus.CANCELED, service.orders.value.single().status)
    }

    @Test
    fun accountSwitchInvalidatesOpenReview() {
        val service = service()
        val confirmation = service.confirmation(stopRequest())
        service.switchAccount(client())

        assertFalse(service.submit(confirmation) { fail("Stale stop submitted") })
        assertTrue(requests.isEmpty())
    }

    private fun service() = StopOrderService(client(), Schedulers.trampoline(), Schedulers.trampoline())

    private fun stopRequest() = requireNotNull(StopOrderRequest.fromInput(StopSide.SELL, "0.01", "5000000"))

    private fun triggerEvent() = ParentOrderEvent(
        "FX_BTC_JPY", "parent-1", "acceptance-1", "2026-01-01T00:00:00Z",
        "TRIGGER", null, null, "MARKET", 1, "child-1", "SELL", null, 0.01, null
    )

    private fun client(): APIClientV1 {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (status, body) = respond(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("").body(body.toResponseBody()).build()
        }.build()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        return Retrofit.Builder().baseUrl("https://example.test/v1/").client(client)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build().create(APIClientV1::class.java)
    }

    private fun respond(request: Request): Pair<Int, String> = when (request.url.encodedPath) {
        "/v1/me/sendparentorder" -> 200 to """{"parent_order_acceptance_id":"acceptance-1"}"""
        "/v1/me/getparentorders" -> {
            assertEquals("FX_BTC_JPY", request.url.queryParameter("product_code"))
            if (request.url.queryParameter("parent_order_state") == "ACTIVE" && parentState != "ACTIVE") 200 to "[]"
            else 200 to parentOrders()
        }
        "/v1/me/getparentorder" -> {
            assertEquals("parent-1", request.url.queryParameter("parent_order_id"))
            200 to """{"order_method":"SIMPLE","parameters":[{"product_code":"FX_BTC_JPY",
                    "condition_type":"STOP","trigger_price":5000000}]}"""
        }
        "/v1/me/getchildorders" -> 200 to childOrders
        "/v1/me/cancelparentorder" -> {
            if (cancelStatus == 200) parentState = "CANCELED"
            cancelStatus to ""
        }
        else -> error("Unexpected request ${request.url}")
    }

    private fun parentOrders() = """
        [{"parent_order_id":"parent-1","parent_order_acceptance_id":"acceptance-1","product_code":"FX_BTC_JPY",
          "parent_order_type":"STOP","parent_order_state":"$parentState","side":"SELL","size":0.01,
          "executed_size":$executedSize},
         {"parent_order_type":"STOP_LIMIT","product_code":"FX_BTC_JPY"},
         {"parent_order_type":"STOP","product_code":"BTC_JPY"}]
    """.trimIndent()

    private fun assertJsonBody(request: Request, expected: String) {
        val actual = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))
    }
}
