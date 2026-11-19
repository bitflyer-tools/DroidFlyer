package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState.OrderRow
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OrderAdapterTest {
    private val adapter = OrderAdapter(null)
    private val firstOrder = order(1)
    private val secondOrder = order(2)

    private var response = emptyList<ChildOrder>()
    private val requests = mutableListOf<CancelChildOrderRequest>()
    private val client = Proxy.newProxyInstance(
        APIClientV1::class.java.classLoader,
        arrayOf(APIClientV1::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getPositions", "getCollateral" -> Observable.empty<Any>()
            "getChildOrders" -> Observable.just(response)
            "cancelChildOrder" -> Completable.fromAction { requests.add(args[0] as CancelChildOrderRequest) }
            else -> error("Unexpected API call: ${method.name}")
        }
    } as APIClientV1
    private val session = PrivateTradingState.Session(client)

    @Before
    fun useSynchronousSchedulers() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
    }

    @Test
    fun boundCancelActionUsesItsOriginalAccountAndOrderAfterRefresh() {
        val state = PrivateTradingState(client)
        val presenter = OrdersPresenter(state) {}
        presenter.orders.subscribe(adapter::updateOrders)
        adapter.clickCancel.subscribe(presenter::cancelOrder)
        val row = createRow()
        val cancel = row.findViewById<View>(R.id.cancel)

        response = listOf(firstOrder)
        state.refresh()
        adapter.onBindViewHolder(adapter.ViewHolder(row), 0)

        response = listOf(secondOrder, firstOrder.copy())
        state.refresh()
        cancel.performClick()
        val expected = listOf(CancelChildOrderRequest(firstOrder.productCode, firstOrder.childOrderAcceptanceId))
        assertEquals(expected, requests)

        response = listOf(secondOrder)
        state.refresh()
        cancel.performClick()
        assertEquals(expected, requests)

        response = listOf(firstOrder.copy())
        state.switchAccount(client)
        assertEquals(1, adapter.itemCount)
        cancel.performClick()
        assertEquals(expected, requests)
    }

    @Test
    fun equalResponsesNotifyOnlyOnce() {
        var updates = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updates++
            }
        })

        updateOrders(listOf(firstOrder, secondOrder))
        updateOrders(listOf(firstOrder.copy(), secondOrder.copy()))

        assertEquals(1, updates)
        assertEquals(2, adapter.itemCount)

        val row = createRow()
        val canceledOrders = adapter.clickCancel.test()
        adapter.onBindViewHolder(adapter.ViewHolder(row), 0)
        row.findViewById<View>(R.id.cancel).performClick()

        assertEquals(firstOrder, canceledOrders.values().single().order)
    }

    @Test
    fun changedMovedAndRemovedOrdersUpdateDisplayAndCancellation() {
        val row = createRow()
        val holder = adapter.ViewHolder(row)
        val canceledOrders = adapter.clickCancel.test()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (adapter.itemCount > 0) adapter.onBindViewHolder(holder, 0)
            }
        })
        updateOrders(listOf(firstOrder, secondOrder))

        val changedOrder = firstOrder.copy(price = 200)
        updateOrders(listOf(changedOrder, secondOrder))
        assertEquals("200 JPY", row.findViewById<TextView>(R.id.price).text.toString())
        row.findViewById<View>(R.id.cancel).performClick()

        updateOrders(listOf(secondOrder, changedOrder))
        row.findViewById<View>(R.id.cancel).performClick()

        updateOrders(listOf(changedOrder))
        assertEquals(1, adapter.itemCount)
        row.findViewById<View>(R.id.cancel).performClick()

        adapter.updateOrders(emptyList())
        assertEquals(0, adapter.itemCount)
        assertEquals(listOf(changedOrder, secondOrder, changedOrder), canceledOrders.values().map { it.order })
    }

    private fun updateOrders(orders: List<ChildOrder>) = adapter.updateOrders(orders.map { OrderRow(session, it) })

    private fun createRow() = LinearLayout(RuntimeEnvironment.getApplication()).apply {
        listOf(R.id.type, R.id.price, R.id.size, R.id.cancel).forEach { id ->
            addView(TextView(context).apply { this.id = id })
        }
    }

    private fun order(id: Int) = ChildOrder(
        id = id,
        childOrderId = "order-$id",
        productCode = "FX_BTC_JPY",
        side = "BUY",
        childOrderType = "LIMIT",
        price = 100,
        average_price = 0,
        size = 0.1,
        childOrderState = "ACTIVE",
        childOrderAcceptanceId = "acceptance-$id",
        outstandingSize = 0.1,
        cancelSize = 0.0,
        executedSize = 0.0,
        totalCommission = 0.0
    )
}
