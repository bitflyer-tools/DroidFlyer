package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class ClosePositionServiceTest {
    private val scheduler = TestScheduler()
    private val orders = mutableListOf<SendChildOrderRequest>()
    private val positions = ArrayDeque<Double>()
    private val orderUpdates = ArrayDeque<Pair<String, Double>>()
    private val orderReadErrors = ArrayDeque<Throwable>()
    private val positionReadErrors = ArrayDeque<Throwable>()
    private var orderReads = 0
    private val api = Proxy.newProxyInstance(
        APIClientV1::class.java.classLoader, arrayOf(APIClientV1::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getPositions" -> (if (orders.isEmpty()) null else positionReadErrors.poll())
                ?.let { Observable.error<List<Position>>(it) }
                ?: Observable.just(positions.remove().toPositions())
            "sendChildOrder" -> {
                orders.add(args[0] as SendChildOrderRequest)
                Observable.just(ChildOrderAcceptance("order-${orders.size}"))
            }
            "getChildOrders" -> {
                assertEquals(null, args[1])
                assertEquals("order-${orders.size}", args[2])
                orderReads++
                orderReadErrors.poll()?.let { Observable.error<List<ChildOrder>>(it) }
                    ?: orderUpdates.remove().let { (state, filled) ->
                        Observable.just(listOf(childOrder(state, filled)))
                    }
            }
            else -> error("Unexpected API call: ${method.name}")
        }
    } as APIClientV1
    private val service = ClosePositionService(api, scheduler)

    @Test
    fun `splits a twelve BTC CFD long into two capped sell orders`() {
        positions.addAll(listOf(12.0, 2.0, 0.0))
        orderUpdates.addAll(listOf("COMPLETED" to 10.0, "COMPLETED" to 2.0))

        val acceptedAfter = mutableListOf<Int>()
        service.close(12.0) { acceptedAfter.add(orders.size) }.test().assertResult(Unit)

        assertEquals(listOf(1), acceptedAfter)
        assertOrders("SELL", 10.0, 2.0)
    }

    @Test
    fun `reduces the first order when the capped order would leave less than the minimum`() {
        positions.addAll(listOf(10.0005, 0.001, 0.0))
        orderUpdates.addAll(listOf("COMPLETED" to 9.9995, "COMPLETED" to 0.001))

        service.close(10.0005).test().assertResult(Unit)

        assertOrders("SELL", 9.9995, 0.001)
    }

    @Test
    fun `keeps the capped order when it leaves exactly the minimum`() {
        positions.addAll(listOf(-10.001, -0.001, 0.0))
        orderUpdates.addAll(listOf("COMPLETED" to 10.0, "COMPLETED" to 0.001))

        service.close(-10.001).test().assertResult(Unit)

        assertOrders("BUY", 10.0, 0.001)
    }

    @Test
    fun `rejects an initial position below the minimum without submitting an order`() {
        positions.add(0.00099999)

        service.close(0.00099999).test().assertError(IllegalStateException::class.java)

        assertOrders("SELL")
    }

    @Test
    fun `does not submit an undersized order after a partial fill`() {
        positions.addAll(listOf(10.0, 0.0005))
        orderUpdates.add("CANCELED" to 9.9995)

        service.close(10.0).test().assertError(IllegalStateException::class.java)

        assertOrders("SELL", 10.0)
    }

    @Test
    fun `waits for a partial fill to settle and for the position to reflect it`() {
        positions.addAll(listOf(-12.0, -12.0, -5.0, 0.0))
        orderUpdates.addAll(listOf("ACTIVE" to 7.0, "CANCELED" to 7.0, "COMPLETED" to 5.0))

        val result = service.close(-12.0).test()
        assertOrders("BUY", 10.0)
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        assertOrders("BUY", 10.0)
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)

        result.assertResult(Unit)
        assertOrders("BUY", 10.0, 5.0)
    }

    @Test
    fun `stops when a refreshed position is flat after a partial fill`() {
        positions.addAll(listOf(12.0, 0.0))
        orderUpdates.add("CANCELED" to 7.0)

        service.close(12.0).test().assertResult(Unit)

        assertOrders("SELL", 10.0)
    }

    @Test
    fun `stops if the position has reversed since confirmation`() {
        positions.add(-12.0)

        service.close(12.0).test().assertError(IllegalStateException::class.java)

        assertOrders("SELL")
    }

    @Test
    fun `does not submit when the initial position has shrunk since confirmation`() {
        positions.add(11.0)

        service.close(12.0).test().assertError(IllegalStateException::class.java)

        assertOrders("SELL")
    }

    @Test
    fun `does not submit another order when the position never reflects the fill`() {
        positions.addAll(listOf(12.0) + List(16) { 12.0 })
        orderUpdates.add("COMPLETED" to 10.0)

        val result = service.close(12.0).test()
        scheduler.advanceTimeBy(15, TimeUnit.SECONDS)

        result.assertError(TimeoutException::class.java)
        assertOrders("SELL", 10.0)
    }

    @Test
    fun `retries failed reads before closing the remaining position`() {
        positions.addAll(listOf(12.0, 2.0, 0.0))
        orderUpdates.addAll(listOf("COMPLETED" to 10.0, "COMPLETED" to 2.0))
        orderReadErrors.add(httpError(503))
        positionReadErrors.add(IOException("Connection lost"))

        val result = service.close(12.0).test()
        assertEquals(1, orderReads)
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        assertEquals(2, orderReads)
        assertOrders("SELL", 10.0)
        result.assertNotComplete().assertNoErrors()

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        result.assertResult(Unit)
        assertOrders("SELL", 10.0, 2.0)
    }

    @Test
    fun `times out persistent order read failures without resubmitting`() {
        positions.add(12.0)
        orderReadErrors.addAll(List(16) { httpError(503) })

        val result = service.close(12.0).test()
        scheduler.advanceTimeBy(14, TimeUnit.SECONDS)
        result.assertNotComplete().assertNoErrors()
        assertEquals(15, orderReads)

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        result.assertError(TimeoutException::class.java)
        assertOrders("SELL", 10.0)
    }

    @Test
    fun `propagates permanent order read errors immediately`() {
        positions.add(12.0)
        val error = httpError(403)
        orderReadErrors.add(error)

        service.close(12.0).test().assertError(error)
        assertOrders("SELL", 10.0)
    }

    private fun httpError(status: Int): HttpException =
        HttpException(Response.error<Any>(status, "".toResponseBody()))

    private fun assertOrders(side: String, vararg sizes: Double) {
        assertEquals(sizes.map { SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, it, timeInForce = "IOC") }, orders)
    }

    private fun Double.toPositions(): List<Position> =
        if (this == 0.0) emptyList() else listOf(
            Position("FX_BTC_JPY", if (this > 0.0) "BUY" else "SELL", 0, kotlin.math.abs(this), 0.0, 0.0, 0.0, 1, 0.0)
        )

    private fun childOrder(state: String, filled: Double): ChildOrder {
        val request = orders.last()
        val outstanding = if (state == "ACTIVE") request.size - filled else 0.0
        return ChildOrder(
            orders.size, "child-${orders.size}", request.productCode, request.side, request.childOrderType,
            0, 0, request.size, state, "order-${orders.size}", outstanding, 0.0, filled, 0.0
        )
    }
}
