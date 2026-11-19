package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BoardAdapterTest {
    @Test
    fun clickingAskRowsEmitsTheirGroupedPricesForSellConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Ask)
        val exactAsk = Order(100L, 1.0)
        val offBucketAsk = Order(101L, 2.0)
        adapter.groupSize.value = 10L
        adapter.items.value = listOf(exactAsk, offBucketAsk)

        val row = createRow(context)
        val holder = adapter.ViewHolder(row)
        val selectedAsks = adapter.clickItems.test()

        adapter.onBindViewHolder(holder, 0)
        row.performClick()
        adapter.onBindViewHolder(holder, 1)
        row.performClick()

        selectedAsks.assertValues(exactAsk, offBucketAsk.copy(price = 110L))
    }

    @Test
    fun personalQuantityTracksOrderChangesAndRegroupingAcrossBoardUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Ask)
        val row = createRow(context)
        val holder = adapter.ViewHolder(row)
        val personalSize = row.findViewById<TextView>(R.id.mineSize)
        val order = activeOrder("SELL", 101, 0.08)
        adapter.groupSize.value = 10L
        adapter.items.value = listOf(Order(102L, 1.0))
        adapter.myOrders.value = listOf(order)

        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, personalSize.visibility)
        assertEquals("0.08", personalSize.text.toString())

        adapter.myOrders.value = listOf(order.copy(outstandingSize = 0.03))
        adapter.items.value = listOf(Order(103L, 2.0))
        adapter.onBindViewHolder(holder, 0)
        assertEquals("0.03", personalSize.text.toString())

        adapter.groupSize.value = 1L
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, personalSize.visibility)

        adapter.groupSize.value = 10L
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, personalSize.visibility)
        assertEquals("0.03", personalSize.text.toString())

        adapter.myOrders.value = emptyList()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, personalSize.visibility)
        assertEquals("", personalSize.text.toString())
    }

    @Test
    fun publicBoardUpdatesReusePersonalQuantitiesWithoutReadingOrders() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Bid)
        val row = createRow(context)
        val holder = adapter.ViewHolder(row)
        var orderReads = 0
        adapter.myOrders.value = object : AbstractList<ChildOrder>() {
            override val size = 1

            override fun get(index: Int): ChildOrder {
                orderReads++
                return activeOrder("BUY", 100, 0.04)
            }
        }
        val indexedOrderReads = orderReads

        listOf(1.0, 2.0).forEach { size ->
            adapter.items.value = listOf(Order(100L, size))
            adapter.onBindViewHolder(holder, 0)
            assertEquals("0.04", row.findViewById<TextView>(R.id.mineSize).text.toString())
        }
        assertEquals(indexedOrderReads, orderReads)
    }

    private fun createRow(context: Context) = RelativeLayout(context).apply {
        listOf(R.id.price, R.id.size, R.id.mineSize).forEach { id ->
            addView(TextView(context).apply { this.id = id })
        }
        addView(View(context).apply { id = R.id.backgroundView })
    }
}
