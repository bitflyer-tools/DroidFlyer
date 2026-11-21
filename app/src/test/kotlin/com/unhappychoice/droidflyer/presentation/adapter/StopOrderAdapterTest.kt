package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
