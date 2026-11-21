package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
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
    fun switchingClearsAllPrivateDataAndIgnoresResponsesFromThePreviousAccount() {
        val initial = state.refresh().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        initial.assertComplete()
        assertPrivateData(listOf(position), 10.0, listOf(order))
        val previousRow = displayedRows().single()

        state.refresh().test()
        scheduler.triggerActions()
        state.switchAccount(accountB.client)
        state.refresh().test()
        assertPrivateData(emptyList(), 0.0, emptyList())
        assertNull(state.cancelOrder(previousRow))
        scheduler.triggerActions()

        accountA.publish(position, 999.0, order)
        scheduler.triggerActions()
        assertPrivateData(emptyList(), 0.0, emptyList())

        val newPosition = position.copy(size = 1.0)
        val newOrder = order.copy()
        accountB.publish(newPosition, 20.0, newOrder)
        scheduler.triggerActions()
        assertPrivateData(listOf(newPosition), 20.0, listOf(newOrder))
        val displayedRow = displayedRows().single()
        state.refresh().test()
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
    fun switchingToAnUnconfiguredAccountClearsPrivateDataAndSkipsRefresh() {
        state.refresh().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()

        val previousRow = displayedRows().single()
        state.switchAccount(null)
        state.refresh().test().assertComplete()
        scheduler.triggerActions()

        assertNull(state.cancelOrder(previousRow))
        assertFalse(accountA.positions.hasObservers())
        assertFalse(accountA.collateral.hasObservers())
        assertFalse(accountA.orders.hasObservers())
        assertPrivateData(emptyList(), 0.0, emptyList())
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
        state.refresh().test()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        scheduler.triggerActions()
        val oldConfirmation = checkNotNull(state.closeConfirmation())

        state.switchAccount(accountB.client)
        state.refresh().test()
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
        state.refresh().test()
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
        state.refresh().test()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        val confirmation = checkNotNull(state.closeConfirmation())
        val unsubmitted = checkNotNull(state.closePosition(confirmation))

        state.refresh().test()
        scheduler.triggerActions()
        val row = displayedRows().single()
        val cancellation = checkNotNull(state.cancelOrder(row)).test()
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.5)
        val submission = checkNotNull(state.submitOrder(request)).test()
        accountA.publish(position, 99.0, order)
        state.unlink()
        assertPrivateData(emptyList(), 0.0, emptyList())
        assertNull(state.closePosition(confirmation))
        assertNull(state.cancelOrder(row))
        assertNull(state.submitOrder(request))

        val staleOrder = unsubmitted.test()
        state.refresh().test()
        scheduler.triggerActions()
        assertPrivateData(emptyList(), 0.0, emptyList())
        staleOrder.assertNoValues().assertComplete()
        cancellation.assertNoValues().assertComplete()
        submission.assertNoValues().assertComplete()
        assertEquals(emptyList<SendChildOrderRequest>(), accountA.sent)
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)

        state.switchAccount(accountB.client)
        state.refresh().test()
        scheduler.triggerActions()
        accountB.publish(position, 20.0, order)
        scheduler.triggerActions()
        assertPrivateData(listOf(position), 20.0, listOf(order))
    }

    private fun displayedRows() = state.orderRows().blockingFirst()

    private fun publishOrders(vararg orders: ChildOrder) {
        state.refresh().test()
        scheduler.triggerActions()
        accountA.orders.onNext(orders.toList())
        scheduler.triggerActions()
    }

    private fun assertPrivateData(positions: List<Position>, balance: Double, orders: List<ChildOrder>) {
        assertEquals(positions, state.position.value)
        assertEquals(balance, state.balance.value, 0.0)
        assertEquals(orders, state.orders.value)
    }

    private class TradingApi {
        val positions = PublishSubject.create<List<Position>>()
        val collateral = PublishSubject.create<Map<String, Any>>()
        val orders = PublishSubject.create<List<ChildOrder>>()
        val sent = mutableListOf<SendChildOrderRequest>()
        val cancelled = mutableListOf<CancelChildOrderRequest>()
        val client = Proxy.newProxyInstance(
            APIClientV1::class.java.classLoader,
            arrayOf(APIClientV1::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getPositions" -> positions.take(1)
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

        fun publish(position: Position, balance: Double, order: ChildOrder) {
            positions.onNext(listOf(position))
            collateral.onNext(mapOf("collateral" to balance))
            orders.onNext(listOf(order))
        }
    }
}
