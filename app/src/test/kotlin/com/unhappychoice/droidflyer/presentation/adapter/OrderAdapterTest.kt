package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OrderAdapterTest {
    private val adapter = OrderAdapter(null)
    private val firstOrder = order(1)
    private val secondOrder = order(2)

    @Test
    fun equalResponsesNotifyOnlyOnce() {
        var updates = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updates++
            }
        })

        adapter.updateOrders(listOf(firstOrder, secondOrder))
        adapter.updateOrders(listOf(firstOrder.copy(), secondOrder.copy()))

        assertEquals(1, updates)
        assertEquals(2, adapter.itemCount)
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
        adapter.updateOrders(listOf(firstOrder, secondOrder))

        val changedOrder = firstOrder.copy(price = 200)
        adapter.updateOrders(listOf(changedOrder, secondOrder))
        assertEquals("200 JPY", row.findViewById<TextView>(R.id.price).text.toString())
        row.findViewById<View>(R.id.cancel).performClick()

        adapter.updateOrders(listOf(secondOrder, changedOrder))
        row.findViewById<View>(R.id.cancel).performClick()

        adapter.updateOrders(listOf(changedOrder))
        assertEquals(1, adapter.itemCount)
        row.findViewById<View>(R.id.cancel).performClick()

        adapter.updateOrders(emptyList())
        assertEquals(0, adapter.itemCount)
        canceledOrders.assertValues(changedOrder, secondOrder, changedOrder)
    }

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
