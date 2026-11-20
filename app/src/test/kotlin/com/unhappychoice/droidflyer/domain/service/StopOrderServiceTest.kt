package com.unhappychoice.droidflyer.domain.service

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.socket.client.Socket
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit.SECONDS

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
    fun childExecutionsRefreshTriggeredStopThroughPartialAndFullExecution() {
        val scheduler = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { scheduler }
        try {
            val api = client()
            val preference = APITokenPreference(mock(Context::class.java))
            val realtime = RealtimeClient(GsonBuilder().create(), preference, mock(Socket::class.java))
            val status = CurrentStatusService(api, realtime, api)
            try {
                val stops = status.privateState.stopOrders
                stops.refresh()
                scheduler.triggerActions()
                assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

                realtime.parentOrderEvents.onNext(listOf(triggerEvent().copy(productCode = "BTC_JPY")))
                scheduler.triggerActions()
                assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

                realtime.parentOrderEvents.onNext(listOf(triggerEvent()))
                scheduler.triggerActions()
                assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
                assertFalse(stops.orders.value.single().canCancel)
                scheduler.advanceTimeBy(1, SECONDS)

                executedSize = 0.005
                val execution = childExecution()
                realtime.childOrderEvents.onNext(listOf(
                    execution.copy(productCode = "BTC_JPY"),
                    execution.copy(eventType = "ORDER")
                ))
                scheduler.advanceTimeBy(1, SECONDS)
                assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

                executedSize = 0.0
                childOrders = marketChild(0.005)
                realtime.childOrderEvents.onNext(listOf(execution))
                scheduler.advanceTimeBy(1, SECONDS)
                assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, stops.orders.value.single().status)

                parentState = "COMPLETED"
                childOrders = marketChild(0.01)
                realtime.childOrderEvents.onNext(listOf(execution.copy(execId = 2)))
                scheduler.advanceTimeBy(1, SECONDS)
                assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)
            } finally {
                status.dispose()
            }
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    @Test
    fun reloadRecoversTriggerWithoutRealtimeEvent() {
        childOrders = marketChild(0.0)
        val service = service()

        service.refresh()

        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        val childRequest = requests.first { it.url.encodedPath == "/v1/me/getchildorders" }
        assertEquals("parent-1", childRequest.url.queryParameter("parent_order_id"))
        assertEquals("FX_BTC_JPY", childRequest.url.queryParameter("product_code"))
    }

    @Test
    fun normalRefreshPreservesAndUpdatesExecutionRecoveredOnReconnect() {
        val service = service()
        service.refresh()

        childOrders = marketChild(0.005)
        service.refresh(recoverTriggers = true)
        assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, service.orders.value.single().status)

        childOrders = "[]"
        service.refresh()
        assertEquals(0.005, service.orders.value.single().parent.executedSize, 0.0)
        assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, service.orders.value.single().status)

        childOrders = marketChild(0.01)
        service.refresh()
        assertEquals(StopOrderStatus.EXECUTED, service.orders.value.single().status)
    }

    @Test
    fun canceledStopRetainsAndRefreshesItsPartialExecution() {
        val service = service()
        executedSize = 0.003
        service.refresh()

        parentState = "CANCELED"
        executedSize = 0.0
        childOrders = marketChild(0.005)
        service.refresh()

        val order = service.orders.value.single()
        assertEquals(StopOrderStatus.CANCELED, order.status)
        assertEquals(0.005, order.parent.executedSize, 0.0)
        assertFalse(order.canCancel)
    }

    @Test
    fun expiryEventPreservesPartialExecutionWhileParentStateIsActive() {
        val service = service()
        executedSize = 0.005
        service.refresh()

        service.onEvents(listOf(triggerEvent().copy(eventType = "EXPIRE")))

        val order = service.orders.value.single()
        assertEquals(StopOrderStatus.EXPIRED, order.status)
        assertEquals(0.005, order.parent.executedSize, 0.0)
        assertFalse(order.canCancel)
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

    private fun marketChild(size: Double) = """[{"child_order_type":"MARKET","executed_size":$size}]"""

    private fun stopRequest() = requireNotNull(StopOrderRequest.fromInput(StopSide.SELL, "0.01", "5000000"))

    private fun triggerEvent() = ParentOrderEvent(
        "FX_BTC_JPY", "parent-1", "acceptance-1", "2026-01-01T00:00:00Z",
        "TRIGGER", null, null, "MARKET", 1, "child-1", "SELL", null, 0.01, null
    )

    private fun childExecution() = ChildOrderEvent(
        "FX_BTC_JPY", "child-1", "child-acceptance-1", "2026-01-01T00:00:01Z",
        "EXECUTION", null, null, null, 1, "SELL", 5000000, 0.005, null, null
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
        "/v1/board" -> 200 to """{"mid_price":0,"bids":[],"asks":[]}"""
        "/v1/me/getpositions" -> 200 to "[]"
        "/v1/me/getcollateral" -> 200 to """{"collateral":0}"""
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
