package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.domain.service.StopOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class StopOrderAdapterTest {
    @Test
    fun partiallyExecutedTerminalOrdersShowStatusAndExecutedQuantity() {
        assertTerminalRow("CANCELED", 0.005, "Canceled (0.005 BTC executed)")
        assertTerminalRow("EXPIRED", 0.005, "Expired (0.005 BTC executed)")
    }

    @Test
    fun terminalOrdersWithoutExecutionsShowOnlyTheStatus() {
        assertTerminalRow("CANCELED", 0.0, "Canceled")
        assertTerminalRow("EXPIRED", 0.0, "Expired")
    }

    @Test
    fun recoveryKeepsUnknownStatusAndOffersVerification() {
        val parent = ParentOrder("parent-1", "acceptance-1", "FX_BTC_JPY", "STOP", "ACTIVE", "SELL", 0.01, 0.0)
        val adapter = StopOrderAdapter {}
        val container = LinearLayout(RuntimeEnvironment.getApplication())
        val holder = adapter.onCreateViewHolder(container, 0)
        val order = StopOrder(parent, 5000000L, waitingConfirmed = false, recoveryAvailable = true)
        adapter.updateOrders(listOf(order))

        adapter.onBindViewHolder(holder, 0)

        val row = holder.itemView as LinearLayout
        val details = row.getChildAt(0) as TextView
        val action = row.getChildAt(1) as TextView
        assertEquals("Status unavailable", details.text.toString().lineSequence().elementAt(1).substringAfter(" · "))
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals("Verify to cancel", action.text.toString())
    }

    @Test
    fun refreshingOneOrderOnlyNotifiesThatRowInsteadOfTheWholeList() {
        val parent1 = ParentOrder("parent-1", "acceptance-1", "FX_BTC_JPY", "STOP", "ACTIVE", "SELL", 0.01, 0.0)
        val parent2 = ParentOrder("parent-2", "acceptance-2", "FX_BTC_JPY", "STOP", "ACTIVE", "SELL", 0.01, 0.0)
        val adapter = StopOrderAdapter {}
        adapter.updateOrders(listOf(StopOrder(parent1, 5000000L), StopOrder(parent2, 6000000L)))
        val changes = adapter.recordChanges()

        val refreshedParent2 = parent2.copy(parentOrderState = "COMPLETED")
        adapter.updateOrders(listOf(StopOrder(parent1, 5000000L), StopOrder(refreshedParent2, 6000000L)))

        assertEquals(listOf("change 1 1"), changes)
    }

    @Test
    fun cancellingSetChangesOnlyNotifyTheAffectedRow() {
        val parent1 = ParentOrder("parent-1", "acceptance-1", "FX_BTC_JPY", "STOP", "ACTIVE", "SELL", 0.01, 0.0)
        val parent2 = ParentOrder("parent-2", "acceptance-2", "FX_BTC_JPY", "STOP", "ACTIVE", "SELL", 0.01, 0.0)
        val adapter = StopOrderAdapter {}
        adapter.updateOrders(listOf(StopOrder(parent1, 5000000L), StopOrder(parent2, 6000000L)))
        val changes = adapter.recordChanges()

        adapter.updateCancellations(setOf("acceptance-2"))

        assertEquals(listOf("change 1 1"), changes)
    }

    private fun StopOrderAdapter.recordChanges() = mutableListOf<String>().also { changes ->
        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { changes.add("invalidate") }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                changes.add("change $positionStart $itemCount")
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { changes.add("insert") }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { changes.add("remove") }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { changes.add("move") }
        })
    }

    private fun assertTerminalRow(state: String, executedSize: Double, status: String) {
        val parent = ParentOrder("parent-1", "acceptance-1", "FX_BTC_JPY", "STOP", state, "SELL", 0.01, executedSize)
        val adapter = StopOrderAdapter {}
        val container = LinearLayout(RuntimeEnvironment.getApplication())
        val holder = adapter.onCreateViewHolder(container, 0)
        adapter.updateOrders(listOf(StopOrder(parent, 5000000L)))

        adapter.onBindViewHolder(holder, 0)

        val row = holder.itemView as LinearLayout
        val details = row.getChildAt(0) as TextView
        assertEquals(status, details.text.toString().lineSequence().elementAt(1).substringAfter(" · "))
        assertEquals(View.GONE, row.getChildAt(1).visibility)
    }
}
