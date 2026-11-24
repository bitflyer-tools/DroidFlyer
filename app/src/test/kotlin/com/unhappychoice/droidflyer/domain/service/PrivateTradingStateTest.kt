package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ActiveOrdersStatus
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.ReadStatus
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.lang.reflect.Proxy

class PrivateTradingStateTest {
    private val scheduler = TestScheduler()
    private val accountA = TradingApi()
    private val accountB = TradingApi()
    private val state = PrivateTradingState(accountA.client, scheduler, scheduler)
    private val position = Position("FX_BTC_JPY", "BUY", 100, 0.5, 0.0, 0.0, 10.0, 2, 0.0)
    private val order = ChildOrder(
        1, "order", "FX_BTC_JPY", "SELL", "LIMIT", 110, 0, 0.5, "ACTIVE",
        "acceptance", 0.5, 0.0, 0.0, 0.0
    )

    @Test
    fun initialFailureIsUnavailableAndSuccessfulEmptyResponseClearsIt() {
        assertEquals(ActiveOrdersStatus.INITIAL, state.activeOrdersStatus.value)
        failOrdersRefresh()
        assertEquals(ActiveOrdersStatus.UNAVAILABLE, state.activeOrdersStatus.value)
        assertEquals(emptyList<ChildOrder>(), state.orders.value)

        publishOrders()
        assertEquals(ActiveOrdersStatus.EMPTY, state.activeOrdersStatus.value)
        assertEquals(emptyList<ChildOrder>(), state.orders.value)
    }

    @Test
    fun failedRefreshKeepsRowsAndSuccessfulRefreshReplacesThem() {
        publishOrders(order)
        val row = displayedRows().single()

        failOrdersRefresh()
        assertEquals(ActiveOrdersStatus.STALE, state.activeOrdersStatus.value)
        assertSame(row.order, displayedRows().single().order)

        val updatedOrder = order.copy(price = 120)
        publishOrders(updatedOrder)
        assertEquals(ActiveOrdersStatus.AVAILABLE, state.activeOrdersStatus.value)
        assertEquals(updatedOrder, displayedRows().single().order)
    }

    @Test
    fun unchangedSuccessfulResponseClearsStaleStatusAndKeepsOrderReference() {
        publishOrders(order)
        failOrdersRefresh()

        publishOrders(order.copy())
        assertEquals(ActiveOrdersStatus.AVAILABLE, state.activeOrdersStatus.value)
        assertSame(order, displayedRows().single().order)
    }

    @Test
    fun switchingAccountsClearsStaleStatusAndIgnoresQueuedFailure() {
        publishOrders(order)
        failOrdersRefresh()
        state.refreshActiveOrders().test()
        scheduler.triggerActions()
        accountA.orders.onError(ordersUnavailable())

        state.switchAccount(accountB.client)
        scheduler.triggerActions()

        assertEquals(ActiveOrdersStatus.INITIAL, state.activeOrdersStatus.value)
        assertEquals(emptyList<ChildOrder>(), state.orders.value)
    }

    @Test
    fun rejectedCollateralStaysUnavailableUntilZeroIsLoaded() {
        accountA.client.getBalance().test().assertValue(emptyList()).assertComplete()
        val totals = observeTradingTotals(
            Observable.just(MarketPrice(120.0)), state.position.asObservable(), state.collateral.asObservable(), scheduler
        ).test()

        val rejected = refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onNext(emptyList())
        accountA.orders.onNext(emptyList())
        accountA.collateral.onError(SecurityException("Missing collateral permission"))
        scheduler.triggerActions()
        rejected.assertComplete()
        val unavailable = TradingTotals(0L, 120L, null, 120L)
        totals.assertValue(unavailable)

        accountA.collateral = PublishSubject.create()
        val loaded = refreshAll().test()
        scheduler.triggerActions()
        accountA.collateral.onNext(mapOf("collateral" to 0))
        accountA.positions.onNext(emptyList())
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()
        loaded.assertComplete()
        totals.assertValueAt(totals.valueCount() - 1, TradingTotals(0L, 120L, 0L, 120L)).assertNoErrors()
    }

    @Test
    fun initialPositionAndCollateralFailuresAreUnavailable() {
        assertEquals(ReadStatus.INITIAL, state.positionStatus.value)
        assertEquals(ReadStatus.INITIAL, state.collateralStatus.value)

        val refresh = refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onError(ordersUnavailable())
        accountA.collateral.onError(ordersUnavailable())
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()

        refresh.assertComplete()
        assertEquals(ReadStatus.UNAVAILABLE, state.positionStatus.value)
        assertEquals(ReadStatus.UNAVAILABLE, state.collateralStatus.value)
    }

    @Test
    fun positionFailureAfterSuccessMarksItStaleWhileCollateralStaysCurrent() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        accountA.collateral.onNext(mapOf("collateral" to 10.0))
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()
        assertEquals(ReadStatus.AVAILABLE, state.positionStatus.value)
        assertEquals(ReadStatus.AVAILABLE, state.collateralStatus.value)

        accountA.positions = PublishSubject.create()
        accountA.collateral = PublishSubject.create()
        accountA.orders = PublishSubject.create()
        val refresh = refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onError(ordersUnavailable())
        accountA.collateral.onNext(mapOf("collateral" to 20.0))
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()

        refresh.assertComplete()
        assertEquals(ReadStatus.STALE, state.positionStatus.value)
        assertEquals(listOf(position), state.position.value)
        assertEquals(ReadStatus.AVAILABLE, state.collateralStatus.value)
        assertEquals(20.0, state.collateral.value.amount)
        assertNotNull(state.closeConfirmation())
    }

    @Test
    fun collateralFailureAfterSuccessMarksItStaleWhilePositionStaysCurrent() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        accountA.collateral.onNext(mapOf("collateral" to 10.0))
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()
        assertEquals(ReadStatus.AVAILABLE, state.positionStatus.value)
        assertEquals(ReadStatus.AVAILABLE, state.collateralStatus.value)

        accountA.positions = PublishSubject.create()
        accountA.collateral = PublishSubject.create()
        accountA.orders = PublishSubject.create()
        val refresh = refreshAll().test()
        scheduler.triggerActions()
        val updatedPosition = position.copy(size = 1.0)
        accountA.positions.onNext(listOf(updatedPosition))
        accountA.collateral.onError(ordersUnavailable())
        accountA.orders.onNext(emptyList())
        scheduler.triggerActions()

        refresh.assertComplete()
        assertEquals(ReadStatus.AVAILABLE, state.positionStatus.value)
        assertEquals(listOf(updatedPosition), state.position.value)
        assertEquals(ReadStatus.STALE, state.collateralStatus.value)
        assertEquals(10.0, state.collateral.value.amount)
    }

    @Test
    fun switchingClearsAllPrivateDataAndIgnoresResponsesFromThePreviousAccount() {
        val initial = refreshAll().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        initial.assertComplete()
        assertEquals(listOf(false, false, false), accountA.refreshObservers())
        assertPrivateData(listOf(position), 10.0, listOf(order))
        val previousRow = displayedRows().single()

        refreshAll().test()
        scheduler.triggerActions()
        assertEquals(listOf(true, true, true), accountA.refreshObservers())
        state.switchAccount(accountB.client)
        assertEquals(listOf(false, false, false), accountA.refreshObservers())
        refreshAll().test()
        assertPrivateData(emptyList(), null, emptyList())
        assertNull(state.cancelOrder(previousRow))
        scheduler.triggerActions()

        accountA.publish(position, 999.0, order)
        scheduler.triggerActions()
        assertPrivateData(emptyList(), null, emptyList())

        val newPosition = position.copy(size = 1.0)
        val newOrder = order.copy()
        accountB.publish(newPosition, 20.0, newOrder)
        scheduler.triggerActions()
        assertPrivateData(listOf(newPosition), 20.0, listOf(newOrder))
        val displayedRow = displayedRows().single()
        refreshAll().test()
        scheduler.triggerActions()
        accountB.publish(newPosition, 20.0, newOrder.copy())
        scheduler.triggerActions()
        assertSame(displayedRow.order, state.orders.value.single())
        assertNull(state.cancelOrder(previousRow))
        val cancellation = checkNotNull(state.cancelOrder(displayedRow)).test()
        scheduler.triggerActions()
        cancellation.assertValue(Unit).assertComplete()
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)
        assertEquals(listOf(CancelChildOrderRequest("FX_BTC_JPY", order.childOrderAcceptanceId)), accountB.cancelled)
    }

    @Test
    fun disposalCancelsPrivateRefreshesAndPreventsAnotherRefresh() {
        refreshAll().test()
        scheduler.triggerActions()
        assertEquals(listOf(true, true, true), accountA.refreshObservers())

        state.dispose()
        refreshAll().test()
        state.switchAccount(accountB.client)
        scheduler.triggerActions()

        assertEquals(listOf(false, false, false), accountA.refreshObservers())
        assertEquals(listOf(false, false, false), accountB.refreshObservers())
    }

    @Test
    fun switchingToAnUnconfiguredAccountClearsPrivateDataAndSkipsRefresh() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()

        val previousRow = displayedRows().single()
        state.switchAccount(null)
        refreshAll().test().assertComplete()
        scheduler.triggerActions()

        assertNull(state.cancelOrder(previousRow))
        assertFalse(accountA.positions.hasObservers())
        assertFalse(accountA.collateral.hasObservers())
        assertFalse(accountA.orders.hasObservers())
        assertPrivateData(emptyList(), null, emptyList())
    }

    @Test
    fun displayedCancellationRequiresAnActiveOrderWithBothIdentifiers() {
        publishOrders(order)
        val row = displayedRows().single()

        publishOrders(
            order.copy(productCode = "BTC_JPY"),
            order.copy(childOrderAcceptanceId = "another-order")
        )

        assertNull(state.cancelOrder(row))
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)
    }

    @Test
    fun oldConfirmationStaysInvalidEvenWhenTheNewAccountLoadsTheSamePosition() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        scheduler.triggerActions()
        val oldConfirmation = checkNotNull(state.closeConfirmation())

        state.switchAccount(accountB.client)
        refreshAll().test()
        assertNull(state.closeConfirmation())
        assertNull(state.closePosition(oldConfirmation))

        scheduler.triggerActions()
        accountB.positions.onNext(listOf(position))
        scheduler.triggerActions()
        assertNull(state.closePosition(oldConfirmation) { error("Reconfirmed on another account") })

        val confirmation = checkNotNull(state.closeConfirmation())
        val result = checkNotNull(state.closePosition(confirmation)).test()
        scheduler.triggerActions()
        accountB.positions.onNext(listOf(position))
        scheduler.triggerActions()
        accountB.positions.onNext(emptyList())
        scheduler.triggerActions()
        result.assertComplete()
        assertEquals(emptyList<SendChildOrderRequest>(), accountA.sent)
        assertEquals(
            listOf(SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5, timeInForce = "IOC")),
            accountB.sent
        )
    }

    @Test
    fun changingPositionWithinTheSameAccountRequiresFreshConfirmation() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        scheduler.triggerActions()
        val original = checkNotNull(state.closeConfirmation())

        state.position.value = listOf(position.copy(side = "SELL", size = 1.0))
        var updated: PrivateTradingState.CloseConfirmation? = null
        assertNull(state.closePosition(original) { updated = it })
        assertEquals(emptyList<SendChildOrderRequest>(), accountA.sent)

        val result = checkNotNull(state.closePosition(checkNotNull(updated))).test()
        scheduler.triggerActions()
        accountA.positions.onNext(state.position.value)
        scheduler.triggerActions()
        accountA.positions.onNext(emptyList())
        scheduler.triggerActions()
        result.assertComplete()
        assertEquals(listOf(SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 1.0, timeInForce = "IOC")), accountA.sent)
    }

    @Test
    fun unlinkClearsDataAndRejectsQueuedResponsesAndStaleOrderActions() {
        refreshAll().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        assertEquals(ActiveOrdersStatus.AVAILABLE, state.activeOrdersStatus.value)
        val confirmation = checkNotNull(state.closeConfirmation())
        val unsubmitted = checkNotNull(state.closePosition(confirmation))

        refreshAll().test()
        scheduler.triggerActions()
        val row = displayedRows().single()
        val cancellation = checkNotNull(state.cancelOrder(row)).test()
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.5)
        val prepared = checkNotNull(state.prepareOrder(request))
        val submission = checkNotNull(state.submitOrder(prepared)).test()
        accountA.publish(position, 99.0, order)
        state.unlink()
        assertEquals(ActiveOrdersStatus.INITIAL, state.activeOrdersStatus.value)
        assertPrivateData(emptyList(), null, emptyList())
        assertNull(state.closePosition(confirmation))
        assertNull(state.cancelOrder(row))
        assertNull(state.prepareOrder(request))
        assertNull(state.submitOrder(prepared))

        val staleOrder = unsubmitted.test()
        refreshAll().test()
        scheduler.triggerActions()
        assertPrivateData(emptyList(), null, emptyList())
        staleOrder.assertNoValues().assertComplete()
        cancellation.assertNoValues().assertComplete()
        submission.assertNoValues().assertComplete()
        assertEquals(emptyList<SendChildOrderRequest>(), accountA.sent)
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)

        state.switchAccount(accountB.client)
        refreshAll().test()
        scheduler.triggerActions()
        accountB.publish(position, 20.0, order)
        scheduler.triggerActions()
        assertPrivateData(listOf(position), 20.0, listOf(order))
    }

    @Test
    fun unlinkNotifiesFillHistoryAndStopsPreviousAccountRequests() {
        val changes = state.accountChanges.test()
        val request = state.getMyExecutions(50, null)
        val pending = request.test()
        scheduler.triggerActions()
        accountA.executions.onNext(emptyList())

        state.unlink()
        val stale = request.test()
        scheduler.triggerActions()

        changes.assertValue(Unit)
        pending.assertNoValues().assertError(NoSuchElementException::class.java)
        stale.assertNoValues().assertError(NoSuchElementException::class.java)
        state.getMyExecutions(50, null).test().assertError(IllegalStateException::class.java)
        assertFalse(accountA.executions.hasObservers())
    }

    private fun refreshAll(): Completable =
        Completable.mergeArray(state.refreshPositionsAndCollateral(), state.refreshActiveOrders())

    private fun displayedRows() = state.orderRows().blockingFirst()

    private fun publishOrders(vararg orders: ChildOrder) {
        state.refreshActiveOrders().test()
        scheduler.triggerActions()
        accountA.orders.onNext(orders.toList())
        scheduler.triggerActions()
    }

    private fun failOrdersRefresh() {
        val refresh = state.refreshActiveOrders().test()
        scheduler.triggerActions()
        accountA.orders.onError(ordersUnavailable())
        scheduler.triggerActions()
        refresh.assertComplete()
        accountA.orders = PublishSubject.create()
    }

    private fun ordersUnavailable() = HttpException(Response.error<Any>(503, "".toResponseBody()))

    private fun assertPrivateData(positions: List<Position>, balance: Double?, orders: List<ChildOrder>) {
        assertEquals(positions, state.position.value)
        assertEquals(balance, state.collateral.value.amount)
        assertEquals(orders, state.orders.value)
    }

    private class TradingApi {
        var positions = PublishSubject.create<List<Position>>()
        var collateral = PublishSubject.create<Map<String, Any>>()
        var orders = PublishSubject.create<List<ChildOrder>>()
        val executions = PublishSubject.create<List<PrivateExecution>>()
        val sent = mutableListOf<SendChildOrderRequest>()
        val cancelled = mutableListOf<CancelChildOrderRequest>()
        val client = Proxy.newProxyInstance(
            APIClientV1::class.java.classLoader,
            arrayOf(APIClientV1::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPositions" -> positions.take(1)
                "getMyExecutions" -> executions.take(1)
                "getBalance" -> Observable.just(emptyList<Map<String, Any>>())
                "getCollateral" -> collateral.take(1)
                "getChildOrders" -> if (args[2] == null) orders.take(1) else Observable.just(listOf(
                    ChildOrder(1, "order", "FX_BTC_JPY", sent.last().side, "MARKET", 0, 0,
                        sent.last().size, "COMPLETED", "acceptance", 0.0, 0.0, sent.last().size, 0.0)
                ))
                "sendChildOrder" -> Observable.fromCallable {
                    sent.add(args[0] as SendChildOrderRequest)
                    ChildOrderAcceptance("acceptance")
                }
                "cancelChildOrder" -> Completable.fromAction {
                    cancelled.add(args[0] as CancelChildOrderRequest)
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as APIClientV1

        fun refreshObservers() = listOf(positions.hasObservers(), collateral.hasObservers(), orders.hasObservers())

        fun publish(position: Position, balance: Double, order: ChildOrder) {
            positions.onNext(listOf(position))
            collateral.onNext(mapOf("collateral" to balance))
            orders.onNext(listOf(order))
        }
    }
}
