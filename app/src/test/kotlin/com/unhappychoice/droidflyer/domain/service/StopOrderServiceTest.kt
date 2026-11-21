package com.unhappychoice.droidflyer.domain.service

import android.content.Context
import com.unhappychoice.droidflyer.domain.service.StopOrderService.CancellationFailure
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
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class StopOrderServiceTest {
    private val requests = mutableListOf<Request>()
    private var parentOrderStatus = 200
    private var parentState = "ACTIVE"
    private var size = 0.01
    private var executedSize = 0.0
    private var childOrders = "[]"
    private var childOrderStatus = 200
    private var activeParentPage: ((String?) -> String)? = null
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
    fun continuousChildExecutionsRefreshTriggeredStopThroughPartialAndFullExecution() =
        withRealtimeStops { stops, realtime, scheduler ->
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
            scheduler.advanceTimeBy(9, SECONDS)

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
            repeat(40) { index ->
                realtime.childOrderEvents.onNext(listOf(execution.copy(execId = index + 1L)))
                scheduler.advanceTimeBy(500, MILLISECONDS)
            }
            assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, stops.orders.value.single().status)

            parentState = "COMPLETED"
            childOrders = marketChild(0.01)
            repeat(24) { index ->
                realtime.childOrderEvents.onNext(listOf(execution.copy(execId = index + 41L)))
                scheduler.advanceTimeBy(500, MILLISECONDS)
            }
            assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)
        }

    @Test
    fun childExecutionRecoversMissedTriggerWhileParentIsStillActive() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)
            assertTrue(stops.orders.value.single().canCancel)
            requests.clear()

            childOrders = marketChild(0.005)
            realtime.childOrderEvents.onNext(listOf(childExecution()))
            scheduler.advanceTimeBy(9, SECONDS)

            val order = stops.orders.value.single()
            assertEquals("ACTIVE", order.parent.parentOrderState)
            assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, order.status)
            assertFalse(order.canCancel)
            assertTrue(requests.any {
                it.url.encodedPath == "/v1/me/getchildorders" &&
                    it.url.queryParameter("parent_order_id") == "parent-1"
            })
        }

    @Test
    fun retriesFailedParentListAfterChildExecutionWithoutAnotherEvent() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            parentOrderStatus = 503
            childOrders = marketChild(0.005)
            realtime.childOrderEvents.onNext(listOf(childExecution()))
            scheduler.advanceTimeBy(1, SECONDS)
            assertTrue(stops.loadFailed.value)
            assertFalse(stops.orders.value.single().canCancel)

            parentOrderStatus = 200
            scheduler.advanceTimeBy(38, SECONDS)
            assertFalse(stops.loadFailed.value)
            assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, stops.orders.value.single().status)
        }

    @Test
    fun retriesFailedMarketChildReadAfterTriggerWithoutAnotherEvent() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            childOrderStatus = 503
            realtime.parentOrderEvents.onNext(listOf(triggerEvent()))
            scheduler.advanceTimeBy(9, SECONDS)
            assertTrue(stops.loadFailed.value)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            childOrderStatus = 200
            childOrders = marketChild(0.01)
            scheduler.advanceTimeBy(38, SECONDS)
            assertFalse(stops.loadFailed.value)
            assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)
            requests.clear()
            scheduler.advanceTimeBy(60, SECONDS)
            assertTrue(stopReadPaths().isEmpty())
        }

    @Test
    fun limitExecutionDoesNotRefreshWaitingStops() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            requests.clear()

            realtime.childOrderEvents.onNext(listOf(childExecution().copy(childOrderType = "LIMIT")))
            scheduler.advanceTimeBy(30, SECONDS)

            assertTrue(stopReadPaths().isEmpty())
            assertTrue(stops.orders.value.single().canCancel)
        }

    @Test
    fun childUpdatesPaceReadsAndCoalesceDuringAnActiveRefresh() =
        withRealtimeStops { stops, realtime, scheduler ->
            activeParentPage = {
                (1..2).joinToString(prefix = "[", postfix = "]") {
                    """{"id":$it,"parent_order_id":"parent-$it","parent_order_acceptance_id":"acceptance-$it",
                       "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE",
                       "side":"SELL","size":0.01}"""
                }
            }
            stops.refresh()
            scheduler.triggerActions()
            assertEquals(2, stops.orders.value.count { it.canCancel })
            requests.clear()
            val execution = childExecution()

            realtime.childOrderEvents.onNext(listOf(execution))
            scheduler.advanceTimeBy(1, SECONDS)
            assertEquals(listOf("/v1/me/getparentorders"), stopReadPaths())

            repeat(3) {
                realtime.childOrderEvents.onNext(listOf(execution))
                scheduler.advanceTimeBy(2, SECONDS)
            }
            assertEquals(2, stopReadPaths().size)

            scheduler.advanceTimeBy(2, SECONDS)
            assertEquals("/v1/me/getchildorders", stopReadPaths().last())
            assertEquals(3, stopReadPaths().size)

            scheduler.advanceTimeBy(4, SECONDS)
            assertEquals("/v1/me/getchildorders", stopReadPaths().last())
            assertEquals(4, stopReadPaths().size)

            scheduler.advanceTimeBy(30, SECONDS)
            assertEquals(8, stopReadPaths().size)
        }

    @Test
    fun parentUpdatesApplyImmediatelyAndCoalesceWhileStopReadsArePaced() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            requests.clear()
            activeParentPage = {
                parentOrders().dropLast(1) +
                    """,{"id":2,"parent_order_id":"parent-2","parent_order_acceptance_id":"acceptance-2",
                        "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE",
                        "side":"SELL","size":0.01}]"""
            }

            realtime.parentOrderEvents.onNext(listOf(triggerEvent()))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertTrue(stopReadPaths().isEmpty())

            repeat(16) {
                realtime.parentOrderEvents.onNext(listOf(triggerEvent()))
                scheduler.advanceTimeBy(1, SECONDS)
            }
            assertEquals(listOf(
                "/v1/me/getparentorders", "/v1/me/getparentorders",
                "/v1/me/getchildorders", "/v1/me/getparentorder"
            ), stopReadPaths())

            scheduler.advanceTimeBy(40, SECONDS)
            assertEquals(8, stopReadPaths().size)
            assertEquals(4, stopReadPaths().count { it == "/v1/me/getparentorders" })
            assertEquals(stopReadPaths().size, requests.size)
        }

    @Test
    fun childFailureRefreshesTriggeredStop() =
        assertChildTermination("ORDER_FAILED", "REJECTED", StopOrderStatus.REJECTED, 0.0)

    @Test
    fun childCancellationRefreshesPartiallyExecutedStop() =
        assertChildTermination("CANCEL", "CANCELED", StopOrderStatus.CANCELED, 0.005)

    @Test
    fun childExpiryRefreshesPartiallyExecutedStop() =
        assertChildTermination("EXPIRE", "EXPIRED", StopOrderStatus.EXPIRED, 0.005)

    @Test
    fun refreshRetainsChildTerminationWhenRestNoLongerReturnsTheChild() {
        childOrders = marketChild(0.005, "CANCELED")
        val service = service()
        service.refresh()
        assertEquals(StopOrderStatus.CANCELED, service.orders.value.single().status)

        childOrders = "[]"
        service.refresh()

        val order = service.orders.value.single()
        assertEquals(StopOrderStatus.CANCELED, order.status)
        assertEquals(0.005, order.parent.executedSize, 0.0)
        assertFalse(order.canCancel)
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
    fun failedReconnectLookupDisablesCancellationUntilTriggerIsRecovered() {
        val service = service()
        service.refresh()
        val confirmation = service.cancellationConfirmation(service.orders.value.single())

        childOrderStatus = 500
        service.refresh(recoverTriggers = true)

        assertEquals(StopOrderStatus.UNKNOWN, service.orders.value.single().status)
        assertFalse(service.orders.value.single().canCancel)
        assertTrue(service.loadFailed.value)
        val failures = mutableListOf<CancellationFailure>()
        service.cancel(confirmation) { failures += it }
        assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })

        childOrderStatus = 200
        childOrders = marketChild(0.0)
        service.refresh()

        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        assertFalse(service.loadFailed.value)
    }

    @Test
    fun pollsStopOrdersWhenRealtimeNeverConnectsAtStartup() =
        withRealtimeStops(connected = false) { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(12, SECONDS)
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

            childOrders = marketChild(0.0)
            scheduler.advanceTimeBy(38, SECONDS)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            childOrders = marketChild(0.01)
            scheduler.advanceTimeBy(38, SECONDS)
            assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)

            realtime.privateChannelsReady.onNext(Unit)
            scheduler.triggerActions()
            val requestCount = stopReadPaths().size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, stopReadPaths().size)
        }

    @Test
    fun disconnectRestoresCancellationAfterSuccessfulRestPoll() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            assertTrue(stops.orders.value.single().canCancel)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            parentOrderStatus = 500
            realtime.privateChannelsDisconnected.onNext(Unit)
            scheduler.triggerActions()
            assertCancellationBlocked(stops, confirmation)
            assertTrue(stops.loadFailed.value)

            parentOrderStatus = 200
            scheduler.advanceTimeBy(29, SECONDS)
            assertTrue(stops.loadFailed.value)
            scheduler.advanceTimeBy(9, SECONDS)
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)
            assertTrue(stops.orders.value.single().canCancel)
            assertFalse(stops.loadFailed.value)

            realtime.privateChannelsReady.onNext(Unit)
            scheduler.triggerActions()
            assertTrue(stops.orders.value.single().canCancel)
            val requestCount = requests.size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, requests.size)
        }

    @Test
    fun disconnectedRefreshPacesListsDetailsAndChildLookupsAcrossManyStops() =
        withRealtimeStops { stops, realtime, scheduler ->
            activeParentPage = { before ->
                if (before == null) (201 downTo 102).joinToString(prefix = "[", postfix = "]") {
                    """{"id":$it,"parent_order_id":"parent-$it","parent_order_acceptance_id":"acceptance-$it",
                       "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE","side":"SELL","size":0.01}"""
                } else parentOrders()
            }
            realtime.privateChannelsDisconnected.onNext(Unit)
            scheduler.triggerActions()
            stops.refresh()
            scheduler.triggerActions()
            val initialRequests = stopReadPaths().size
            scheduler.advanceTimeBy(299, SECONDS)

            assertEquals(74, stopReadPaths().size - initialRequests)
            assertEquals(
                listOf("/v1/me/getparentorders", "/v1/me/getparentorders", "/v1/me/getparentorders",
                    "/v1/me/getparentorder", "/v1/me/getchildorders"),
                stopReadPaths().take(5)
            )
            assertTrue(stops.orders.value.any { it.canCancel })

            realtime.privateChannelsReady.onNext(Unit)
            scheduler.triggerActions()
            assertEquals(101, stops.orders.value.size)
            val requestCount = stopReadPaths().size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, stopReadPaths().size)
        }

    @Test
    fun cancellationRechecksForTriggerBeforeNextRestPoll() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            realtime.privateChannelAuthenticationFailed.onNext(Unit)
            scheduler.advanceTimeBy(8, SECONDS)
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)
            assertTrue(stops.orders.value.single().canCancel)
            assertFalse(stops.loadFailed.value)

            childOrders = marketChild(0.0)
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.advanceTimeBy(12, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun cancellationRechecksForTriggerWhileRealtimeIsConnected() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            realtime.privateChannelsReady.onNext(Unit)
            scheduler.triggerActions()
            val waiting = stops.orders.value.single()
            assertTrue(waiting.canCancel)
            val confirmation = stops.cancellationConfirmation(waiting)

            childOrders = marketChild(0.0)
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.triggerActions()

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun cancellationSkipsPostWhenChildStatusCannotBeVerified() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.triggerActions()
            realtime.privateChannelAuthenticationFailed.onNext(Unit)
            scheduler.advanceTimeBy(8, SECONDS)
            assertTrue(stops.orders.value.single().canCancel)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            childOrderStatus = 500
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.advanceTimeBy(12, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertFalse(stops.orders.value.single().canCancel)
            assertTrue(stops.loadFailed.value)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun parentListFailureBlocksOpenConfirmationAndRetryChecksForMissedTrigger() {
        val stops = service()
        stops.refresh()
        assertTrue(stops.orders.value.single().canCancel)
        val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

        parentOrderStatus = 500
        stops.refresh()
        assertTrue(stops.loadFailed.value)
        assertCancellationBlocked(stops, confirmation)

        parentOrderStatus = 200
        childOrders = marketChild(0.0)
        stops.refresh()
        assertFalse(stops.loadFailed.value)
        assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
        assertCancellationBlocked(stops, confirmation)
    }

    @Test
    fun findsWaitingStopOnLaterActiveParentPage() {
        activeParentPage = { before ->
            if (before == null) {
                (200 downTo 101).joinToString(prefix = "[", postfix = "]") {
                    """{"id":$it,"parent_order_type":"STOP_LIMIT","product_code":"FX_BTC_JPY"}"""
                }
            } else {
                assertEquals("101", before)
                parentOrders()
            }
        }
        val service = service()
        service.refresh()

        assertEquals(StopOrderStatus.WAITING, service.orders.value.single().status)
        assertEquals(5000000L, service.orders.value.single().triggerPrice)
        val activeRequests = requests.filter { it.url.queryParameter("parent_order_state") == "ACTIVE" }
        assertEquals(listOf(null, "101"), activeRequests.map { it.url.queryParameter("before") })
        assertTrue(activeRequests.all { it.url.queryParameter("count") == "100" })
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
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        val failures = mutableListOf<CancellationFailure>()
        cancelStatus = 400
        service.cancel(confirmation) { failures += it }
        assertEquals(listOf(CancellationFailure.REQUEST_FAILED), failures)
        assertTrue(service.orders.value.single().canCancel)

        cancelStatus = 200
        service.cancel(confirmation) { fail("Cancellation failed: $it") }
        val cancellations = requests.filter { it.url.encodedPath == "/v1/me/cancelparentorder" }
        assertEquals(2, cancellations.size)
        cancellations.forEach {
            assertEquals("POST", it.method)
            assertJsonBody(it, """{"parent_order_acceptance_id":"acceptance-1","product_code":"FX_BTC_JPY"}""")
        }
        assertEquals(StopOrderStatus.CANCELED, service.orders.value.single().status)
    }

    @Test
    fun cancellationUsesLatestEligibleOrderWithTheSameAcceptanceId() {
        val service = service()
        service.refresh()
        val order = service.orders.value.single()
        val confirmation = service.cancellationConfirmation(order)
        size = 0.02
        service.refresh()
        assertNotEquals(order, service.orders.value.single())

        service.cancel(confirmation) { fail("Cancellation failed: $it") }

        assertEquals(1, requests.count { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationReportsWhenOrderPartiallyExecutesWhileDialogIsOpen() {
        val service = service()
        service.refresh()
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        executedSize = 0.005
        service.refresh()
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }

        assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationReportsWhenOrderDisappearsWhileDialogIsOpen() {
        val service = service()
        service.refresh()
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        service.orders.value = emptyList()
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }

        assertEquals(listOf(CancellationFailure.UNAVAILABLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationRejectsOldConfirmationEvenWhenNewAccountHasTheSameOrder() {
        val service = service()
        service.refresh()
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        service.switchAccount(client())
        assertEquals(confirmation.acceptanceId, service.orders.value.single().acceptanceId)
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }

        assertEquals(listOf(CancellationFailure.ACCOUNT_CHANGED), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun accountSwitchInvalidatesOpenReview() {
        val service = service()
        val confirmation = service.confirmation(stopRequest())
        service.switchAccount(client())

        assertFalse(service.submit(confirmation) { fail("Stale stop submitted") })
        assertTrue(requests.isEmpty())
    }

    private fun assertChildTermination(event: String, state: String, status: StopOrderStatus, filled: Double) =
        withRealtimeStops { stops, realtime, scheduler ->
            childOrders = marketChild(filled)
            stops.refresh()
            scheduler.triggerActions()
            assertEquals(
                if (filled == 0.0) StopOrderStatus.TRIGGERED else StopOrderStatus.PARTIALLY_EXECUTED,
                stops.orders.value.single().status
            )

            childOrders = marketChild(filled, state)
            realtime.childOrderEvents.onNext(listOf(childExecution().copy(eventType = event)))
            scheduler.advanceTimeBy(9, SECONDS)

            val order = stops.orders.value.single()
            assertEquals(status, order.status)
            assertEquals(filled, order.parent.executedSize, 0.0)
            assertFalse(order.canCancel)
        }

    private fun assertCancellationBlocked(
        stops: StopOrderService,
        confirmation: StopOrderService.CancellationConfirmation
    ) {
        assertFalse(stops.orders.value.single().canCancel)
        val failures = mutableListOf<CancellationFailure>()
        stops.cancel(confirmation) { failures += it }
        assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    private fun withRealtimeStops(
        connected: Boolean = true,
        test: (StopOrderService, RealtimeClient, TestScheduler) -> Unit
    ) {
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
                if (connected) {
                    realtime.privateChannelsReady.onNext(Unit)
                    scheduler.triggerActions()
                }
                test(status.privateState.stopOrders, realtime, scheduler)
            } finally {
                status.dispose()
            }
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    private fun service() = StopOrderService(client(), Schedulers.trampoline(), Schedulers.trampoline())
        .apply { onPrivateChannelAvailability(true) }

    private fun marketChild(size: Double, state: String = "ACTIVE") =
        """[{"child_order_type":"MARKET","child_order_state":"$state","executed_size":$size}]"""

    private fun stopRequest() = requireNotNull(StopOrderRequest.fromInput(StopSide.SELL, "0.01", "5000000"))

    private fun triggerEvent() = ParentOrderEvent(
        "FX_BTC_JPY", "parent-1", "acceptance-1", "2026-01-01T00:00:00Z",
        "TRIGGER", null, null, "MARKET", 1, "child-1", "SELL", null, 0.01, null
    )

    private fun childExecution() = ChildOrderEvent(
        "FX_BTC_JPY", "child-1", "child-acceptance-1", "2026-01-01T00:00:01Z",
        "EXECUTION", null, null, null, 1, "SELL", 5000000.toBigDecimal(), 0.005, null, null
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
            val active = request.url.queryParameter("parent_order_state") == "ACTIVE"
            val page = activeParentPage
            when {
                parentOrderStatus != 200 -> parentOrderStatus to ""
                page != null && active -> 200 to page(request.url.queryParameter("before"))
                page != null || (active && parentState != "ACTIVE") -> 200 to "[]"
                else -> 200 to parentOrders()
            }
        }
        "/v1/me/getparentorder" -> {
            200 to """{"order_method":"SIMPLE","parameters":[{"product_code":"FX_BTC_JPY",
                    "condition_type":"STOP","trigger_price":5000000}]}"""
        }
        "/v1/me/getchildorders" -> childOrderStatus to
            if (request.url.queryParameter("parent_order_id") == "parent-1") childOrders else "[]"
        "/v1/me/cancelparentorder" -> {
            if (cancelStatus == 200) parentState = "CANCELED"
            cancelStatus to ""
        }
        else -> error("Unexpected request ${request.url}")
    }

    private fun stopReadPaths() = requests.filter {
        it.url.encodedPath.startsWith("/v1/me/getparent") || it.url.queryParameter("parent_order_id") != null
    }.map { it.url.encodedPath }

    private fun parentOrders() = """
        [{"id":100,"parent_order_id":"parent-1","parent_order_acceptance_id":"acceptance-1","product_code":"FX_BTC_JPY",
          "parent_order_type":"STOP","parent_order_state":"$parentState","side":"SELL","size":$size,
          "executed_size":$executedSize},
         {"parent_order_type":"STOP_LIMIT","product_code":"FX_BTC_JPY"},
         {"parent_order_type":"STOP","product_code":"BTC_JPY"}]
    """.trimIndent()

    private fun assertJsonBody(request: Request, expected: String) {
        val actual = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))
    }
}
