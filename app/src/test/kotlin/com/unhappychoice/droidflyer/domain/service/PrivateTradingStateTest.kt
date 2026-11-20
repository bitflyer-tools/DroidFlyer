package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
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
    fun switchingClearsAllPrivateDataAndRefreshesTheNewAccount() {
        state.refresh()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        assertPrivateData(listOf(position), 10.0, listOf(order))

        state.refresh()
        scheduler.triggerActions()
        state.switchAccount(accountB.client)
        assertPrivateData(emptyList(), 0.0, emptyList())
        assertNull(state.cancelOrder(order))
        scheduler.triggerActions()

        accountA.publish(position, 999.0, order)
        scheduler.triggerActions()
        assertPrivateData(emptyList(), 0.0, emptyList())

        val newPosition = position.copy(size = 1.0)
        val newOrder = order.copy()
        accountB.publish(newPosition, 20.0, newOrder)
        scheduler.triggerActions()
        assertPrivateData(listOf(newPosition), 20.0, listOf(newOrder))
        assertNull(state.cancelOrder(order))
        val cancellation = checkNotNull(state.cancelOrder(newOrder)).test()
        scheduler.triggerActions()
        cancellation.assertComplete()
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)
        assertEquals(listOf(CancelChildOrderRequest("FX_BTC_JPY", order.childOrderAcceptanceId)), accountB.cancelled)
    }

    @Test
    fun equalOrderRefreshKeepsDisplayedCancellationValidOnlyForTheCurrentAccount() {
        state.refresh()
        scheduler.triggerActions()
        accountA.orders.onNext(listOf(order))
        scheduler.triggerActions()
        val previousAccountOrder = state.orders.value.single()

        state.switchAccount(accountB.client)
        scheduler.triggerActions()
        accountB.orders.onNext(listOf(order.copy()))
        scheduler.triggerActions()
        val displayedOrder = state.orders.value.single()

        state.refresh()
        scheduler.triggerActions()
        accountB.orders.onNext(listOf(order.copy()))
        scheduler.triggerActions()

        assertSame(displayedOrder, state.orders.value.single())
        assertNull(state.cancelOrder(previousAccountOrder))
        val cancellation = checkNotNull(state.cancelOrder(displayedOrder)).test()
        scheduler.triggerActions()
        cancellation.assertComplete()
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)
        assertEquals(listOf(CancelChildOrderRequest("FX_BTC_JPY", order.childOrderAcceptanceId)), accountB.cancelled)
    }

    @Test
    fun oldConfirmationStaysInvalidEvenWhenTheNewAccountLoadsTheSamePosition() {
        state.refresh()
        scheduler.triggerActions()
        accountA.positions.onNext(listOf(position))
        scheduler.triggerActions()
        val oldConfirmation = checkNotNull(state.closeConfirmation())

        state.switchAccount(accountB.client)
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
        state.refresh()
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
        state.refresh()
        scheduler.triggerActions()
        accountA.publish(position, 10.0, order)
        scheduler.triggerActions()
        val confirmation = checkNotNull(state.closeConfirmation())
        val unsubmitted = checkNotNull(state.closePosition(confirmation))
        val cancellation = checkNotNull(state.cancelOrder(order)).test()

        state.refresh()
        accountA.publish(position, 99.0, order)
        state.unlink()
        assertPrivateData(emptyList(), 0.0, emptyList())
        assertNull(state.closePosition(confirmation))
        assertNull(state.cancelOrder(order))
        assertNull(state.submitOrder(SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.5)))

        val staleOrder = unsubmitted.test()
        state.refresh()
        scheduler.triggerActions()
        assertPrivateData(emptyList(), 0.0, emptyList())
        staleOrder.assertNoValues().assertComplete()
        cancellation.assertNoValues().assertComplete()
        assertEquals(emptyList<SendChildOrderRequest>(), accountA.sent)
        assertEquals(emptyList<CancelChildOrderRequest>(), accountA.cancelled)

        state.switchAccount(accountB.client)
        scheduler.triggerActions()
        accountB.publish(position, 20.0, order)
        scheduler.triggerActions()
        assertPrivateData(listOf(position), 20.0, listOf(order))
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
                    mapOf<String, Any>("child_order_acceptance_id" to "acceptance")
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
