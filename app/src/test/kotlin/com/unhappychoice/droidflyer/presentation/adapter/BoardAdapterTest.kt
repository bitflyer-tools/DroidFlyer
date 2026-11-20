package com.unhappychoice.droidflyer.presentation.adapter

import android.content.Context
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
        val roundedAsk = Order(110L, 2.0)
        adapter.applyRows(listOf(exactAsk, roundedAsk), groupingSize = 10L)

        val row = orderRow(context)
        val holder = adapter.ViewHolder(row)
        val selectedAsks = adapter.clickItems.test()

        adapter.onBindViewHolder(holder, 0)
        row.performClick()
        adapter.onBindViewHolder(holder, 1)
        row.performClick()

        selectedAsks.assertValues(exactAsk, roundedAsk)
    }

    @Test
    fun privateUpdatesRebindOnlyTheChangedBidWithoutABookUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Bid)
        val activeOrder = ChildOrder(
            id = 1, childOrderId = "order", productCode = "BTC_JPY", side = "BUY",
            childOrderType = "LIMIT", price = 109L, average_price = 109L, size = 0.1,
            childOrderState = "ACTIVE", childOrderAcceptanceId = "acceptance",
            outstandingSize = 0.1, cancelSize = 0.0, executedSize = 0.0, totalCommission = 0.0
        )
        adapter.applyRows(listOf(Order(110L, 1.0), Order(100L, 2.0)), groupingSize = 10L)
        adapter.updateMyOrders(listOf(activeOrder))
        val row = orderRow(context)
        val holder = adapter.ViewHolder(row)
        val marker = row.findViewById<TextView>(R.id.mineSize)
        adapter.onBindViewHolder(holder, 1)
        assertEquals(View.VISIBLE, marker.visibility)
        assertEquals("0.1", marker.text.toString())

        val changedPositions = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                assertEquals(1, itemCount)
                changedPositions.add(positionStart)
                adapter.onBindViewHolder(holder, positionStart)
            }
        })

        val partiallyFilled = activeOrder.copy(outstandingSize = 0.04, executedSize = 0.06)
        adapter.updateMyOrders(listOf(partiallyFilled))
        assertEquals("0.04", marker.text.toString())
        assertEquals(View.VISIBLE, marker.visibility)

        adapter.updateMyOrders(emptyList())
        assertEquals(View.GONE, marker.visibility)
        assertEquals("", marker.text.toString())
        assertEquals(listOf(1, 1), changedPositions)
    }

    private fun orderRow(context: Context) = RelativeLayout(context).apply {
        listOf(R.id.price, R.id.size, R.id.mineSize).forEach { id ->
            addView(TextView(context).apply { this.id = id })
        }
        addView(View(context).apply { id = R.id.backgroundView })
    }
}
