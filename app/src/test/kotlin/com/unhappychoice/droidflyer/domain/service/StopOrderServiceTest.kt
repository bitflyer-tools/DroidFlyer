package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.domain.service.StopOrderService.CancellationFailure
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.PrivateEvent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import com.google.gson.JsonParser
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
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
import java.io.IOException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

class StopOrderServiceTest {
    private val requests = mutableListOf<Request>()
    private var parentOrderStatus = 200
    private var historyStatus = 200
    private var parentId = 100L
    private var submissionStatus = 200
    private var loseSubmissionResponse = false
    private var parentState = "ACTIVE"
    private var size = 0.01
    private var executedSize = 0.0
    private var outstandingSize: Double? = size
    private var childOrders = "[]"
    private var childOrderStatus = 200
    private var omitStopFromActiveParents = false
    private var canceledParentRows: String? = null
    private var activeParentPage: ((String?) -> String)? = null
    private var cancelStatus = 200
    private val serviceScheduler = TestScheduler()

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
    fun enforcesQuantityBoundsForCurrentAccountType() {
        val maximum = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM)
        val service = StopOrderService(client(), serviceScheduler, serviceScheduler, maximumOrderSize = maximum)
            .apply { onPrivateChannelAvailability(true) }

        listOf("0.000999999999999999999", "10.000000000000000001").forEach { quantity ->
            val confirmation = service.confirmation(stopRequest(quantity))
            assertFalse(service.submit(confirmation) { fail("Submitted an out-of-range order") })
        }

        maximum.value = CryptoCfdOrderSize.CORPORATE_MAXIMUM
        val aboveCorporateMaximum = service.confirmation(stopRequest("40.000000000000000001"))
        assertFalse(service.submit(aboveCorporateMaximum) { fail("Submitted above the corporate maximum") })
        val corporateConfirmation = service.confirmation(stopRequest("40"))
        maximum.value = CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM
        assertFalse(service.submit(corporateConfirmation) { fail("Submitted after the maximum decreased") })
        serviceScheduler.triggerActions()
        assertTrue(requests.none { it.method == "POST" })

        assertTrue(service.submit(service.confirmation(stopRequest("0.001"))) { assertTrue(it.isSuccess) })
        serviceScheduler.triggerActions()
        assertTrue(service.submit(service.confirmation(stopRequest("10"))) { assertTrue(it.isSuccess) })
        serviceScheduler.triggerActions()
        maximum.value = CryptoCfdOrderSize.CORPORATE_MAXIMUM
        assertTrue(service.submit(corporateConfirmation) { assertTrue(it.isSuccess) })
        serviceScheduler.triggerActions()
        assertEquals(3, requests.count { it.method == "POST" })
    }

    @Test
    fun submitsSimpleStopAndReadsTriggerFromParentDetails() {
        val service = service()
        val received = mutableListOf<String>()
        assertTrue(service.submit(service.confirmation(stopRequest())) { received += it.getOrThrow() })
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(listOf("acceptance-1"), received)
        val request = requests.single { it.method == "POST" }
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
    fun rejectedSubmissionAllowsResendingWithoutWaitingForAnOrder() {
        val service = service()
        val confirmation = service.confirmation(stopRequest())
        val results = mutableListOf<Result<String>>()
        submissionStatus = 400

        assertTrue(service.submit(confirmation) { results += it })
        serviceScheduler.triggerActions()

        assertTrue(results.single().exceptionOrNull() is StopOrderService.SubmissionRejected)
        assertFalse(service.submitting.value)
        assertFalse(service.reconcilingSubmission.value)
        assertEquals(1, requests.count { it.method == "POST" })

        submissionStatus = 200
        assertTrue(service.submit(confirmation) { results += it })
        serviceScheduler.triggerActions()

        assertEquals("acceptance-1", results.last().getOrThrow())
        assertEquals(2, requests.count { it.method == "POST" })
    }

    @Test
    fun matchingOrderDoesNotUnblockSubmissionAfterLostResponse() {
        val service = service()
        val confirmation = service.confirmation(stopRequest())
        loseSubmissionResponse = true
        val results = mutableListOf<Result<String>>()

        assertTrue(service.submit(confirmation) { results += it })
        assertFalse(service.submit(confirmation) { fail("Submitted while sending") })
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertTrue(results.single().isFailure)
        assertTrue(service.reconcilingSubmission.value)
        assertEquals(100L, service.orders.value.single().parent.id)
        assertFalse(service.submit(confirmation) { fail("Submitted while reconciling") })
        val confirmRecovery = requireNotNull(service.confirmSubmissionRecovery())

        parentId = 101
        service.onParentUpdates()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        val matchingOrder = service.orders.value.single()
        assertEquals(101L, matchingOrder.parent.id)
        assertEquals("SELL", matchingOrder.parent.side)
        assertEquals(0.01, matchingOrder.parent.size, 0.0)
        assertEquals(5000000L, matchingOrder.triggerPrice)
        assertTrue(matchingOrder.triggerVerified)
        assertTrue(service.reconcilingSubmission.value)
        assertFalse(service.submit(confirmation) { fail("Submitted after seeing a matching order") })
        assertEquals(1, requests.count { it.method == "POST" })

        assertTrue(confirmRecovery())
        assertFalse(confirmRecovery())
        assertFalse(service.reconcilingSubmission.value)

        loseSubmissionResponse = false
        assertTrue(service.submit(service.confirmation(stopRequest())) { results += it })
        serviceScheduler.triggerActions()
        assertEquals("acceptance-1", results.last().getOrThrow())
    }

    @Test
    fun confirmedRecoveryAllowsManualResubmissionWhenNoMatchingOrderAppears() {
        val service = service()
        val results = mutableListOf<Result<String>>()
        loseSubmissionResponse = true

        assertTrue(service.submit(service.confirmation(stopRequest())) { results += it })
        serviceScheduler.advanceTimeBy(90, SECONDS)
        assertTrue(results.single().isFailure)
        assertTrue(service.reconcilingSubmission.value)

        val confirmRecovery = requireNotNull(service.confirmSubmissionRecovery())
        assertFalse(service.submit(service.confirmation(stopRequest())) { fail("Submitted before recovery was confirmed") })
        assertTrue(confirmRecovery())
        assertFalse(service.reconcilingSubmission.value)
        assertEquals(1, requests.count { it.method == "POST" })

        loseSubmissionResponse = false
        assertTrue(service.submit(service.confirmation(stopRequest())) { results += it })
        serviceScheduler.triggerActions()
        assertEquals("acceptance-1", results.last().getOrThrow())
        assertEquals(2, requests.count { it.method == "POST" })
    }

    @Test
    fun staleRecoveryConfirmationCannotClearAnotherAccountsPendingSubmission() {
        val service = service()
        loseSubmissionResponse = true
        assertTrue(service.submit(service.confirmation(stopRequest())) { assertTrue(it.isFailure) })
        serviceScheduler.triggerActions()
        val confirmRecovery = requireNotNull(service.confirmSubmissionRecovery())

        service.switchAccount(client())
        service.onPrivateChannelAvailability(true)
        assertTrue(service.submit(service.confirmation(stopRequest())) { assertTrue(it.isFailure) })
        serviceScheduler.triggerActions()

        assertFalse(confirmRecovery())
        assertTrue(service.reconcilingSubmission.value)
    }

    @Test
    fun continuousChildExecutionsRefreshTriggeredStopThroughPartialAndFullExecution() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

            realtime.parent(listOf(triggerEvent().copy(productCode = "BTC_JPY")))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

            realtime.parent(listOf(triggerEvent()))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertFalse(stops.orders.value.single().canCancel)
            scheduler.advanceTimeBy(28, SECONDS)

            executedSize = 0.005
            val execution = childExecution()
            realtime.child(listOf(
                execution.copy(productCode = "BTC_JPY"),
                execution.copy(eventType = "ORDER")
            ))
            scheduler.advanceTimeBy(1, SECONDS)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            executedSize = 0.0
            childOrders = marketChild(0.005)
            repeat(40) { index ->
                realtime.child(listOf(execution.copy(execId = index + 1L)))
                scheduler.advanceTimeBy(500, MILLISECONDS)
            }
            assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, stops.orders.value.single().status)

            parentState = "COMPLETED"
            childOrders = marketChild(0.01)
            repeat(24) { index ->
                realtime.child(listOf(execution.copy(execId = index + 41L)))
                scheduler.advanceTimeBy(500, MILLISECONDS)
            }
            assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)
        }

    @Test
    fun childExecutionRecoversMissedTriggerWhileParentIsStillActive() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)
            assertTrue(stops.orders.value.single().canCancel)
            requests.clear()

            childOrders = marketChild(0.005)
            realtime.child(listOf(childExecution()))
            scheduler.advanceTimeBy(28, SECONDS)

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
            scheduler.advanceTimeBy(28, SECONDS)
            parentOrderStatus = 503
            childOrders = marketChild(0.005)
            realtime.child(listOf(childExecution()))
            scheduler.advanceTimeBy(4, SECONDS)
            assertTrue(stops.loadFailed.value)
            assertFalse(stops.orders.value.single().canCancel)

            parentOrderStatus = 200
            scheduler.advanceTimeBy(58, SECONDS)
            assertFalse(stops.loadFailed.value)
            assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, stops.orders.value.single().status)
        }

    @Test
    fun retriesFailedMarketChildReadAfterTriggerWithoutAnotherEvent() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            childOrderStatus = 503
            realtime.parent(listOf(triggerEvent()))
            scheduler.advanceTimeBy(28, SECONDS)
            assertTrue(stops.loadFailed.value)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            childOrderStatus = 200
            childOrders = marketChild(0.01)
            scheduler.advanceTimeBy(58, SECONDS)
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
            scheduler.advanceTimeBy(28, SECONDS)
            requests.clear()

            realtime.child(listOf(childExecution().copy(childOrderType = "LIMIT")))
            scheduler.advanceTimeBy(30, SECONDS)

            assertTrue(stopReadPaths().isEmpty())
            assertTrue(stops.orders.value.single().canCancel)
        }

    @Test
    fun activeStopCanBeCanceledWhenHistoryRequestsFail() =
        withRealtimeStops { stops, _, scheduler ->
            historyStatus = 503
            stops.refresh()
            scheduler.advanceTimeBy(8, SECONDS)
            val order = stops.orders.value.single()
            assertTrue(order.canCancel)

            scheduler.advanceTimeBy(16, SECONDS)
            assertEquals(
                listOf("ACTIVE", "COMPLETED", "CANCELED", null),
                requests.filter { it.url.encodedPath == "/v1/me/getparentorders" }
                    .map { it.url.queryParameter("parent_order_state") }
            )
            assertTrue(stops.loadFailed.value)
            assertTrue(stops.orders.value.single().canCancel)
            stops.cancel(stops.cancellationConfirmation(order)) { fail("Cancellation failed: $it") }
            scheduler.triggerActions()
            assertEquals(1, requests.count { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun loadsExpiredAndRejectedStopHistoryWithoutStateFilters() {
        listOf("EXPIRED" to StopOrderStatus.EXPIRED, "REJECTED" to StopOrderStatus.REJECTED)
            .forEach { (state, expectedStatus) ->
                withRealtimeStops { stops, _, scheduler ->
                    parentState = state
                    requests.clear()
                    stops.refresh()
                    scheduler.advanceTimeBy(60, SECONDS)

                    assertFalse(stops.loadFailed.value)
                    assertEquals(expectedStatus, stops.orders.value.single().status)
                    assertEquals(
                        listOf("ACTIVE", "COMPLETED", "CANCELED", null),
                        requests.filter { it.url.encodedPath == "/v1/me/getparentorders" }
                            .map { it.url.queryParameter("parent_order_state") }
                    )
                }
            }
    }

    @Test
    fun connectedInitialRefreshPacesActiveReadsBeforeHistory() =
        withRealtimeStops { stops, _, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(4, SECONDS)

            assertEquals(listOf("/v1/me/getparentorders", "/v1/me/getparentorder"), stopReadPaths())
            assertTrue(stops.orders.value.isEmpty())

            scheduler.advanceTimeBy(4, SECONDS)

            assertEquals("/v1/me/getchildorders", stopReadPaths().last())
            assertEquals(3, stopReadPaths().size)
            assertTrue(stops.orders.value.single().canCancel)

            scheduler.advanceTimeBy(16, SECONDS)
            assertEquals(6, stopReadPaths().size)
        }

    @Test
    fun initialRefreshKeepsStopUnknownWhenParentQuantityIsNoLongerOutstanding() =
        withRealtimeStops { stops, _, scheduler ->
            outstandingSize = 0.0
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)

            val order = stops.orders.value.single()
            assertTrue("/v1/me/getchildorders" in stopReadPaths())
            assertEquals("ACTIVE", order.parent.parentOrderState)
            assertEquals(StopOrderStatus.UNKNOWN, order.status)
            assertCancellationBlocked(stops, stops.cancellationConfirmation(order))
            assertFalse(stops.loadFailed.value)
        }

    @Test
    fun childUpdatesPaceReadsAndCoalesceDuringAnActiveRefresh() =
        withRealtimeStops { stops, realtime, scheduler ->
            activeParentPage = {
                (1..2).joinToString(prefix = "[", postfix = "]") {
                    """{"id":$it,"parent_order_id":"parent-$it","parent_order_acceptance_id":"acceptance-$it",
                       "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE",
                       "side":"SELL","size":0.01,"outstanding_size":0.01,"cancel_size":0}"""
                }
            }
            stops.refresh()
            scheduler.advanceTimeBy(36, SECONDS)
            assertEquals(2, stops.orders.value.count { it.canCancel })
            requests.clear()
            val execution = childExecution()

            realtime.child(listOf(execution))
            scheduler.advanceTimeBy(1, SECONDS)
            assertEquals(listOf("/v1/me/getparentorders"), stopReadPaths())

            repeat(3) {
                realtime.child(listOf(execution))
                scheduler.advanceTimeBy(2, SECONDS)
            }
            assertEquals(listOf("/v1/me/getparentorders", "/v1/me/getchildorders"), stopReadPaths())

            scheduler.advanceTimeBy(2, SECONDS)
            assertEquals("/v1/me/getchildorders", stopReadPaths().last())
            assertEquals(3, stopReadPaths().size)

            scheduler.advanceTimeBy(52, SECONDS)
            assertEquals(12, stopReadPaths().size)
        }

    @Test
    fun parentUpdatesApplyImmediatelyAndCoalesceWhileStopReadsArePaced() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            requests.clear()
            activeParentPage = {
                parentOrders().dropLast(1) +
                    """,{"id":2,"parent_order_id":"parent-2","parent_order_acceptance_id":"acceptance-2",
                        "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE",
                        "side":"SELL","size":0.01,"outstanding_size":0.01,"cancel_size":0}]"""
            }

            realtime.parent(listOf(triggerEvent()))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertTrue(stopReadPaths().isEmpty())

            repeat(16) {
                realtime.parent(listOf(triggerEvent()))
                scheduler.advanceTimeBy(1, SECONDS)
            }
            assertEquals(
                listOf("/v1/me/getparentorders", "/v1/me/getchildorders", "/v1/me/getparentorder",
                    "/v1/me/getchildorders"),
                stopReadPaths()
            )

            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(12, stopReadPaths().size)
            assertEquals(8, stopReadPaths().count { it == "/v1/me/getparentorders" })
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
    fun expiryBeforeTriggerRemainsVisibleWhenRestOmitsTheUnfilledChild() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())
            val expiry = childExecution().copy(eventType = "EXPIRE")

            realtime.child(listOf(expiry))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.WAITING, stops.orders.value.single().status)

            realtime.parent(
                listOf(triggerEvent().copy(childOrderAcceptanceId = expiry.childOrderAcceptanceId))
            )
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.EXPIRED, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)

            scheduler.advanceTimeBy(32, SECONDS)
            val order = stops.orders.value.single()
            assertEquals(StopOrderStatus.EXPIRED, order.status)
            assertEquals(0.0, order.parent.executedSize, 0.0)
            assertFalse(order.canCancel)
        }

    @Test
    fun matchingExpiryShowsImmediatelyAndRestRecoversEarlierPartialFill() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val expiry = childExecution().copy(eventType = "EXPIRE")
            realtime.parent(
                listOf(triggerEvent().copy(childOrderAcceptanceId = expiry.childOrderAcceptanceId))
            )
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            childOrders = marketChild(0.005, "EXPIRED")
            realtime.child(listOf(expiry))
            scheduler.triggerActions()
            assertEquals(StopOrderStatus.EXPIRED, stops.orders.value.single().status)

            scheduler.advanceTimeBy(32, SECONDS)
            val order = stops.orders.value.single()
            assertEquals(StopOrderStatus.EXPIRED, order.status)
            assertEquals(0.005, order.parent.executedSize, 0.0)
        }

    @Test
    fun refreshRetainsChildTerminationWhenRestNoLongerReturnsTheChild() {
        childOrders = marketChild(0.005, "CANCELED")
        val service = service()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(StopOrderStatus.CANCELED, service.orders.value.single().status)

        childOrders = "[]"
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

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
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        val childRequest = requests.first { it.url.encodedPath == "/v1/me/getchildorders" }
        assertEquals("parent-1", childRequest.url.queryParameter("parent_order_id"))
        assertEquals("FX_BTC_JPY", childRequest.url.queryParameter("product_code"))
    }

    @Test
    fun failedReconnectLookupDisablesCancellationUntilTriggerIsRecovered() {
        val service = service()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val confirmation = service.cancellationConfirmation(service.orders.value.single())

        childOrderStatus = 500
        service.refresh(recoverTriggers = true)
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(StopOrderStatus.UNKNOWN, service.orders.value.single().status)
        assertFalse(service.orders.value.single().canCancel)
        assertTrue(service.loadFailed.value)
        val failures = mutableListOf<CancellationFailure>()
        service.cancel(confirmation) { failures += it }
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })

        childOrderStatus = 200
        childOrders = marketChild(0.0)
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(StopOrderStatus.TRIGGERED, service.orders.value.single().status)
        assertFalse(service.loadFailed.value)
    }

    @Test
    fun pollsStopOrdersWhenRealtimeNeverConnectsAtStartup() =
        withRealtimeStops(connected = false) { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)

            childOrders = marketChild(0.0)
            scheduler.advanceTimeBy(58, SECONDS)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)

            childOrders = marketChild(0.01)
            scheduler.advanceTimeBy(58, SECONDS)
            assertEquals(StopOrderStatus.EXECUTED, stops.orders.value.single().status)

            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            val requestCount = stopReadPaths().size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, stopReadPaths().size)
        }

    @Test
    fun blocksCancellationAfterPrivateChannelAuthenticationFailsAtStartup() =
        withRealtimeStops(connected = false) { stops, realtime, scheduler ->
            realtime.available(false)
            scheduler.triggerActions()
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)

            val order = stops.orders.value.single()
            assertEquals(StopOrderStatus.UNKNOWN, order.status)
            assertCancellationBlocked(stops, stops.cancellationConfirmation(order))
        }

    @Test
    fun reconnectKeepsStopUnknownAfterDisconnectedRefreshFails() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertTrue(stops.orders.value.single().canCancel)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            outstandingSize = null
            parentOrderStatus = 500
            realtime.available(false)
            scheduler.advanceTimeBy(4, SECONDS)
            assertCancellationBlocked(stops, confirmation)
            assertTrue(stops.loadFailed.value)

            parentOrderStatus = 200
            scheduler.advanceTimeBy(29, SECONDS)
            assertTrue(stops.loadFailed.value)
            scheduler.advanceTimeBy(29, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)
            assertFalse(stops.loadFailed.value)

            childOrderStatus = 500
            requests.clear()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            assertTrue("/v1/me/getchildorders" in stopReadPaths())
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)

            childOrderStatus = 200
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)
            val requestCount = requests.size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, requests.size)
        }

    @Test
    fun cancellationRechecksRestWaitingEvidenceBeforePosting() =
        withRealtimeStops { stops, _, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val order = stops.orders.value.single()
            assertTrue(order.canCancel)

            outstandingSize = 0.0
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(stops.cancellationConfirmation(order)) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun cancellationAfterReconnectRequiresExplicitRecoveryConfirmation() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val beforeGap = stops.cancellationConfirmation(stops.orders.value.single())

            realtime.available(false)
            scheduler.triggerActions()
            assertFalse(stops.orders.value.single().canConfirmRecovery)
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)

            val order = stops.orders.value.single()
            assertEquals(StopOrderStatus.UNKNOWN, order.status)
            assertTrue(order.canConfirmRecovery)
            assertCancellationBlocked(stops, beforeGap)
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(stops.cancellationConfirmation(order)) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertTrue(failures.isEmpty())
            assertEquals(1, requests.count { it.url.encodedPath == "/v1/me/cancelparentorder" })
            assertEquals(StopOrderStatus.CANCELED, stops.orders.value.single().status)
        }

    @Test
    fun recoveryCancellationRechecksForChildBeforePosting() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            realtime.available(false)
            scheduler.triggerActions()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            val order = stops.orders.value.single()
            assertTrue(order.canConfirmRecovery)

            childOrders = marketChild(0.0, "CANCELED")
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(stops.cancellationConfirmation(order)) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
            assertEquals(StopOrderStatus.CANCELED, stops.orders.value.single().status)
            assertFalse(stops.orders.value.single().canConfirmRecovery)
        }

    @Test
    fun anotherChannelGapInvalidatesRecoveryConfirmation() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            realtime.available(false)
            scheduler.triggerActions()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            val order = stops.orders.value.single()
            assertTrue(order.canConfirmRecovery)
            val confirmation = stops.cancellationConfirmation(order)

            realtime.available(false)
            scheduler.triggerActions()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            assertTrue(stops.orders.value.single().canConfirmRecovery)
            assertCancellationBlocked(stops, confirmation)
        }

    @Test
    fun reconnectKeepsFullyOutstandingStopUnknownWhenRestOmitsUnfilledCanceledChild() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val waiting = stops.orders.value.single()
            assertTrue(waiting.canCancel)
            val confirmation = stops.cancellationConfirmation(waiting)

            realtime.available(false)
            scheduler.advanceTimeBy(28, SECONDS)
            assertCancellationBlocked(stops, confirmation)

            // The parent still looks fully outstanding after the unfilled child was canceled.
            childOrders = "[]"
            requests.clear()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)

            assertTrue(stopReadPaths().contains("/v1/me/getchildorders"))
            val reconnected = stops.orders.value.single()
            assertEquals("ACTIVE", reconnected.parent.parentOrderState)
            assertEquals(size, reconnected.parent.outstandingSize)
            assertEquals(StopOrderStatus.UNKNOWN, reconnected.status)
            assertCancellationBlocked(stops, confirmation)
            assertFalse(stops.loadFailed.value)

            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)
        }

    @Test
    fun reconnectKeepsStopUnknownWhenRestOmitsUnfilledExpiredChild() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            realtime.parent(listOf(triggerEvent().copy(eventType = "ORDER")))
            scheduler.advanceTimeBy(28, SECONDS)
            val order = stops.orders.value.single()
            assertTrue(order.canCancel)
            val confirmation = stops.cancellationConfirmation(order)

            realtime.available(false)
            scheduler.triggerActions()
            // The stop triggers and its unfilled market child expires during the gap.
            // The previously observed parent is no longer listed as open.
            omitStopFromActiveParents = true
            requests.clear()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)

            val reconnected = stops.orders.value.single()
            assertTrue(stopReadPaths().contains("/v1/me/getchildorders"))
            assertEquals("ACTIVE", reconnected.parent.parentOrderState)
            assertEquals(size, reconnected.parent.outstandingSize)
            assertEquals(StopOrderStatus.UNKNOWN, reconnected.status)
            assertFalse(reconnected.canConfirmRecovery)
            assertCancellationBlocked(stops, confirmation)
            assertFalse(stops.loadFailed.value)

            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)
            assertCancellationBlocked(stops, confirmation)
        }

    @Test
    fun newOrderEventAfterReconnectStillRequiresFullyOutstandingRestOrderToCancel() =
        withRealtimeStops { stops, realtime, scheduler ->
            outstandingSize = null
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            realtime.available(false)
            scheduler.triggerActions()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)

            var newOrderOutstandingSize: Double? = null
            activeParentPage = {
                parentOrders().replaceFirst("[", """[
                    {"id":101,"parent_order_id":"parent-2","parent_order_acceptance_id":"acceptance-2",
                     "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"ACTIVE",
                     "side":"SELL","size":0.01,"outstanding_size":$newOrderOutstandingSize,"cancel_size":0},""")
            }
            realtime.parent(listOf(triggerEvent().copy(
                eventType = "ORDER", parentOrderId = "parent-2", parentOrderAcceptanceId = "acceptance-2"
            )))
            scheduler.advanceTimeBy(32, SECONDS)

            val orders = stops.orders.value.associateBy { it.acceptanceId }
            assertEquals(StopOrderStatus.UNKNOWN, orders.getValue("acceptance-1").status)
            val confirmed = orders.getValue("acceptance-2")
            assertEquals(StopOrderStatus.WAITING, confirmed.status)
            assertTrue(confirmed.canCancel)
            val confirmation = stops.cancellationConfirmation(confirmed)
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })

            newOrderOutstandingSize = 0.01
            stops.cancel(confirmation) { fail("Cancellation failed: $it") }
            scheduler.triggerActions()

            val cancellation = requests.single { it.url.encodedPath == "/v1/me/cancelparentorder" }
            assertJsonBody(cancellation, """{"parent_order_acceptance_id":"acceptance-2","product_code":"FX_BTC_JPY"}""")
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
            realtime.available(false)
            scheduler.triggerActions()
            stops.refresh()
            scheduler.triggerActions()
            val initialRequests = stopReadPaths().size
            scheduler.advanceTimeBy(299, SECONDS)

            assertEquals(74, stopReadPaths().size - initialRequests)
            assertEquals(
                listOf("/v1/me/getparentorders", "/v1/me/getparentorders", "/v1/me/getparentorder",
                    "/v1/me/getchildorders", "/v1/me/getparentorder"),
                stopReadPaths().take(5)
            )
            assertTrue(stops.orders.value.isNotEmpty())
            assertTrue(stops.orders.value.all { it.status == StopOrderStatus.UNKNOWN })

            val disconnectedReads = stopReadPaths().size
            realtime.available(true)
            scheduler.advanceTimeBy(299, SECONDS)
            assertEquals(75, stopReadPaths().size - disconnectedReads)
            assertTrue(stops.orders.value.size < 101)

            scheduler.advanceTimeBy(401, SECONDS)
            assertEquals(101, stops.orders.value.size)
            val requestCount = stopReadPaths().size
            scheduler.advanceTimeBy(60, SECONDS)
            assertEquals(requestCount, stopReadPaths().size)
        }

    @Test
    fun reconnectRecoversAnExpiredChildWhenRealtimeEvidenceArrives() =
        withRealtimeStops { stops, realtime, scheduler ->
            outstandingSize = 0.0
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            realtime.available(false)
            scheduler.triggerActions()
            realtime.available(true)
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(StopOrderStatus.UNKNOWN, stops.orders.value.single().status)

            val expiry = childExecution().copy(eventType = "EXPIRE")
            realtime.parent(
                listOf(triggerEvent().copy(childOrderAcceptanceId = expiry.childOrderAcceptanceId))
            )
            realtime.child(listOf(expiry))
            scheduler.triggerActions()

            assertEquals(StopOrderStatus.EXPIRED, stops.orders.value.single().status)
            assertFalse(stops.orders.value.single().canCancel)
        }

    @Test
    fun cancellationRechecksForTriggerWhileRealtimeIsConnected() =
        withRealtimeStops { stops, _, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val waiting = stops.orders.value.single()
            assertTrue(waiting.canCancel)
            val confirmation = stops.cancellationConfirmation(waiting)

            childOrders = marketChild(0.0)
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertEquals(StopOrderStatus.TRIGGERED, stops.orders.value.single().status)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun cancellationIsBlockedWhileRealtimeIsDisconnectedAfterAConnectionGap() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            realtime.available(false)
            scheduler.advanceTimeBy(8, SECONDS)
            assertCancellationBlocked(stops, confirmation)
        }

    @Test
    fun cancellationSkipsPostWhenChildStatusCannotBeVerified() =
        withRealtimeStops { stops, realtime, scheduler ->
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertTrue(stops.orders.value.single().canCancel)
            val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

            childOrderStatus = 500
            val failures = mutableListOf<CancellationFailure>()
            stops.cancel(confirmation) { failures += it }
            scheduler.advanceTimeBy(28, SECONDS)

            assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
            assertFalse(stops.orders.value.single().canCancel)
            assertTrue(stops.loadFailed.value)
            assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
        }

    @Test
    fun parentListFailureBlocksOpenConfirmationAndRetryChecksForMissedTrigger() {
        val stops = service()
        stops.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertTrue(stops.orders.value.single().canCancel)
        val confirmation = stops.cancellationConfirmation(stops.orders.value.single())

        parentOrderStatus = 500
        stops.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertTrue(stops.loadFailed.value)
        assertCancellationBlocked(stops, confirmation)

        parentOrderStatus = 200
        childOrders = marketChild(0.0)
        stops.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
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
        serviceScheduler.advanceTimeBy(32, SECONDS)

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
        serviceScheduler.advanceTimeBy(32, SECONDS)

        childOrders = marketChild(0.005)
        service.refresh(recoverTriggers = true)
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, service.orders.value.single().status)

        childOrders = "[]"
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(0.005, service.orders.value.single().parent.executedSize, 0.0)
        assertEquals(StopOrderStatus.PARTIALLY_EXECUTED, service.orders.value.single().status)

        childOrders = marketChild(0.01)
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(StopOrderStatus.EXECUTED, service.orders.value.single().status)
    }

    @Test
    fun canceledStopRetainsAndRefreshesItsPartialExecution() {
        val service = service()
        executedSize = 0.003
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        parentState = "CANCELED"
        executedSize = 0.0
        childOrders = marketChild(0.005)
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        val order = service.orders.value.single()
        assertEquals(StopOrderStatus.CANCELED, order.status)
        assertEquals(0.005, order.parent.executedSize, 0.0)
        assertFalse(order.canCancel)
    }

    @Test
    fun unchangedCanceledStopsReuseVerifiedChildExecutions() {
        val service = service()
        parentState = "CANCELED"
        canceledParentRows = (1..10).joinToString(prefix = "[", postfix = "]") { id ->
            """{"id":$id,"parent_order_id":"parent-$id","parent_order_acceptance_id":"acceptance-$id",
               "product_code":"FX_BTC_JPY","parent_order_type":"STOP","parent_order_state":"CANCELED",
               "side":"SELL","size":0.01,"executed_size":0.003}"""
        }
        childOrders = marketChild(0.005, "CANCELED")

        service.refresh()
        serviceScheduler.advanceTimeBy(100, SECONDS)
        assertEquals(10, stopReadPaths().count { it == "/v1/me/getchildorders" })
        requests.clear()

        service.refresh()
        serviceScheduler.advanceTimeBy(100, SECONDS)

        assertEquals(List(4) { "/v1/me/getparentorders" }, stopReadPaths())
        val orders = service.orders.value
        assertEquals(10, orders.size)
        orders.forEach {
            assertEquals(StopOrderStatus.CANCELED, it.status)
            assertEquals(0.005, it.parent.executedSize, 0.0)
        }
    }

    @Test
    fun unchangedCanceledStopReverifiesChildDuringAndAfterEventGap() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrders = marketChild(0.005, "CANCELED")
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(0.005, service.orders.value.single().parent.executedSize, 0.0)

        childOrders = marketChild(0.006, "CANCELED")
        requests.clear()
        service.onPrivateChannelAvailability(false)
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.006, service.orders.value.single().parent.executedSize, 0.0)

        childOrders = marketChild(0.007, "CANCELED")
        requests.clear()
        service.onPrivateChannelAvailability(true)
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.007, service.orders.value.single().parent.executedSize, 0.0)

        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(List(4) { "/v1/me/getparentorders" }, stopReadPaths())
    }

    @Test
    fun canceledStopRereadsChildWhenRawParentExecutionChanges() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrders = marketChild(0.005, "CANCELED")
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        executedSize = 0.004
        childOrders = marketChild(0.006, "CANCELED")
        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.006, service.orders.value.single().parent.executedSize, 0.0)
    }

    @Test
    fun canceledStopRereadsChildWhenExecutionEventChanges() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrders = marketChild(0.005, "CANCELED")
        service.onChildEvents(listOf(childExecution()))
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        childOrders = marketChild(0.006, "CANCELED")
        service.onChildEvents(listOf(childExecution().copy(execId = 2)))
        requests.clear()
        service.onChildUpdates()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.006, service.orders.value.single().parent.executedSize, 0.0)
    }

    @Test
    fun canceledStopRereadsChildWhenExecutionEventIsEvictedBeforeRefresh() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrders = marketChild(0.005, "CANCELED")
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        childOrders = marketChild(0.006, "CANCELED")
        val execution = childExecution()
        service.onChildEvents(listOf(execution))
        service.onChildEvents(List(200) { execution.copy(childOrderAcceptanceId = "unrelated-$it") })
        requests.clear()
        service.onChildUpdates()
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.006, service.orders.value.single().parent.executedSize, 0.0)

        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(List(4) { "/v1/me/getparentorders" }, stopReadPaths())
    }

    @Test
    fun fullyExecutedTerminalStopRetriesChildReverificationUntilSuccessful() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrders = marketChild(0.01, "CANCELED")
        service.onChildEvents(listOf(childExecution()))
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(StopOrderStatus.EXECUTED, service.orders.value.single().status)

        service.onChildEvents(listOf(childExecution().copy(execId = 2)))
        childOrderStatus = 503
        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })

        childOrderStatus = 200
        listOf("[]", marketChild(0.01, "CANCELED")).forEach { response ->
            childOrders = response
            requests.clear()
            serviceScheduler.advanceTimeBy(50, SECONDS)
            assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
            assertEquals(StopOrderStatus.EXECUTED, service.orders.value.single().status)
        }

        requests.clear()
        serviceScheduler.advanceTimeBy(50, SECONDS)
        assertTrue(stopReadPaths().isEmpty())

        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(List(4) { "/v1/me/getparentorders" }, stopReadPaths())
    }

    @Test
    fun canceledStopRetriesUnverifiedChildReads() {
        val service = service()
        parentState = "CANCELED"
        executedSize = 0.003
        childOrderStatus = 503
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertFalse(service.orders.value.single().triggerVerified)

        childOrderStatus = 200
        childOrders = "[]"
        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })

        childOrders = marketChild(0.005, "CANCELED")
        requests.clear()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(1, stopReadPaths().count { it == "/v1/me/getchildorders" })
        assertEquals(0.005, service.orders.value.single().parent.executedSize, 0.0)
    }

    @Test
    fun expiryEventPreservesPartialExecutionWhileParentStateIsActive() {
        val service = service()
        executedSize = 0.005
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)

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
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        val failures = mutableListOf<CancellationFailure>()
        cancelStatus = 400
        service.cancel(confirmation) { failures += it }
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(listOf(CancellationFailure.REQUEST_FAILED), failures)
        assertTrue(service.orders.value.single().canCancel)

        cancelStatus = 200
        service.cancel(confirmation) { fail("Cancellation failed: $it") }
        serviceScheduler.advanceTimeBy(32, SECONDS)
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
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val order = service.orders.value.single()
        val confirmation = service.cancellationConfirmation(order)
        size = 0.02
        outstandingSize = size
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertNotEquals(order, service.orders.value.single())

        service.cancel(confirmation) { fail("Cancellation failed: $it") }
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(1, requests.count { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationReportsWhenOrderPartiallyExecutesWhileDialogIsOpen() {
        val service = service()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        executedSize = 0.005
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(listOf(CancellationFailure.INELIGIBLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationReportsWhenOrderDisappearsWhileDialogIsOpen() {
        val service = service()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        service.orders.value = emptyList()
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(listOf(CancellationFailure.UNAVAILABLE), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun cancellationRejectsOldConfirmationEvenWhenNewAccountHasTheSameOrder() {
        val service = service()
        service.refresh()
        serviceScheduler.advanceTimeBy(32, SECONDS)
        val confirmation = service.cancellationConfirmation(service.orders.value.single())
        service.switchAccount(client())
        serviceScheduler.advanceTimeBy(32, SECONDS)
        assertEquals(confirmation.acceptanceId, service.orders.value.single().acceptanceId)
        val failures = mutableListOf<CancellationFailure>()

        service.cancel(confirmation) { failures += it }
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertEquals(listOf(CancellationFailure.ACCOUNT_CHANGED), failures)
        assertFalse(requests.any { it.url.encodedPath == "/v1/me/cancelparentorder" })
    }

    @Test
    fun accountSwitchInvalidatesOpenReview() {
        val service = service()
        val confirmation = service.confirmation(stopRequest())
        service.switchAccount(client())
        serviceScheduler.advanceTimeBy(32, SECONDS)

        assertFalse(service.orderEventsReady)
        assertFalse(service.submit(service.confirmation(stopRequest())) { fail("Stop submitted before authentication") })
        service.onPrivateChannelAvailability(true)
        assertFalse(service.submit(confirmation) { fail("Stale stop submitted") })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun clearingCredentialsStopsPrivateReadsAndSubmissions() {
        val service = service()
        try {
            service.refresh()
            serviceScheduler.advanceTimeBy(32, SECONDS)
            assertTrue(service.orders.value.single().canCancel)
            requests.clear()
            service.switchAccount(null)

            service.refresh()
            serviceScheduler.advanceTimeBy(32, SECONDS)
            assertFalse(service.submit(service.confirmation(stopRequest())) { fail("Stop submitted without credentials") })
            assertTrue(service.orders.value.isEmpty())
            assertTrue(requests.isEmpty())
        } finally {
            service.dispose()
        }
    }

    private fun assertChildTermination(event: String, state: String, status: StopOrderStatus, filled: Double) =
        withRealtimeStops { stops, realtime, scheduler ->
            childOrders = marketChild(filled)
            stops.refresh()
            scheduler.advanceTimeBy(28, SECONDS)
            assertEquals(
                if (filled == 0.0) StopOrderStatus.TRIGGERED else StopOrderStatus.PARTIALLY_EXECUTED,
                stops.orders.value.single().status
            )

            childOrders = marketChild(filled, state)
            realtime.child(listOf(childExecution().copy(eventType = event)))
            scheduler.advanceTimeBy(28, SECONDS)

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
        test: (StopOrderService, StopRealtimeEvents, TestScheduler) -> Unit
    ) {
        val scheduler = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { toV3Scheduler(Schedulers.trampoline()) }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(scheduler) }
        try {
            val api = client()
            val realtime = StopRealtimeEvents()
            val status = CurrentStatusService(
                api, Observable.empty(), Observable.empty(), realtime.children, api, realtime.parents, realtime.availability
            )
            try {
                if (connected) {
                    realtime.available(true)
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

    private class StopRealtimeEvents {
        val children = PublishSubject.create<PrivateEvent<List<ChildOrderEvent>>>()
        val parents = PublishSubject.create<PrivateEvent<List<ParentOrderEvent>>>()
        val availability = PublishSubject.create<PrivateEvent<Boolean>>()

        fun child(events: List<ChildOrderEvent>) = children.onNext(PrivateEvent(events) { true })
        fun parent(events: List<ParentOrderEvent>) = parents.onNext(PrivateEvent(events) { true })
        fun available(ready: Boolean) = availability.onNext(PrivateEvent(ready) { true })
    }

    private fun service() = StopOrderService(client(), serviceScheduler, serviceScheduler)
        .apply { onPrivateChannelAvailability(true) }

    private fun marketChild(size: Double, state: String = "ACTIVE") =
        """[{"child_order_type":"MARKET","child_order_state":"$state","executed_size":$size,
            "child_order_acceptance_id":"child-acceptance-1"}]"""

    private fun stopRequest(quantity: String = "0.01") =
        requireNotNull(StopOrderRequest.fromInput(StopSide.SELL, quantity, "5000000"))

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
        "/v1/me/sendparentorder" -> {
            if (loseSubmissionResponse) throw IOException("Connection closed before receiving the response")
            if (submissionStatus == 200) 200 to """{"parent_order_acceptance_id":"acceptance-1"}"""
            else submissionStatus to """{"status":-1,"error_message":"Invalid order"}"""
        }
        "/v1/me/getparentorders" -> {
            assertEquals("FX_BTC_JPY", request.url.queryParameter("product_code"))
            val state = request.url.queryParameter("parent_order_state")
            val active = state == "ACTIVE"
            val page = activeParentPage
            when {
                parentOrderStatus != 200 -> parentOrderStatus to ""
                state == "EXPIRED" || state == "REJECTED" -> 400 to ""
                !active && historyStatus != 200 -> historyStatus to ""
                page != null && active -> 200 to page(request.url.queryParameter("before"))
                state == "CANCELED" && canceledParentRows != null -> 200 to requireNotNull(canceledParentRows)
                (state != null && state != parentState) || (active && omitStopFromActiveParents) -> 200 to "[]"
                else -> 200 to parentOrders()
            }
        }
        "/v1/me/getparentorder" -> {
            200 to """{"order_method":"SIMPLE","parameters":[{"product_code":"FX_BTC_JPY",
                    "condition_type":"STOP","trigger_price":5000000}]}"""
        }
        "/v1/me/getchildorders" -> {
            val parent = request.url.queryParameter("parent_order_id")
            childOrderStatus to when {
                canceledParentRows != null -> childOrders.replace("child-acceptance-1", "child-$parent")
                parent == "parent-1" -> childOrders
                else -> "[]"
            }
        }
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
        [{"id":$parentId,"parent_order_id":"parent-1","parent_order_acceptance_id":"acceptance-${parentId - 99}","product_code":"FX_BTC_JPY",
          "parent_order_type":"STOP","parent_order_state":"$parentState","side":"SELL","size":$size,
          "executed_size":$executedSize,"outstanding_size":$outstandingSize,"cancel_size":0},
         {"parent_order_type":"STOP_LIMIT","product_code":"FX_BTC_JPY"},
         {"parent_order_type":"STOP","product_code":"BTC_JPY"}]
    """.trimIndent()

    private fun assertJsonBody(request: Request, expected: String) {
        val actual = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(actual))
    }
}
