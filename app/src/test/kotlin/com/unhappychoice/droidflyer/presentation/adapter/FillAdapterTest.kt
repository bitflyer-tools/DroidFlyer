package com.unhappychoice.droidflyer.presentation.adapter

import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FillAdapterTest {
    private val adapter = FillAdapter()
    private val parent = FrameLayout(RuntimeEnvironment.getApplication())

    @Test
    fun appendOlderInsertsOneRangeWithoutTouchingRetainedFills() {
        adapter.replaceAll((1..3).map(::fill))
        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(recordingObserver(events))

        adapter.appendOlder((4..5).map(::fill))

        assertEquals(listOf("inserted:3:2"), events)
        assertEquals(5, adapter.itemCount)
        assertEquals("id-4", boundText(3))
        assertEquals("id-5", boundText(4))
    }

    @Test
    fun appendOlderIgnoresAnEmptyPage() {
        adapter.replaceAll((1..2).map(::fill))
        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(recordingObserver(events))

        adapter.appendOlder(emptyList())

        assertEquals(emptyList<String>(), events)
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun replaceAllDiffsAgainstThePreviousContentOnRefresh() {
        adapter.replaceAll(listOf(fill(1), fill(2)))

        adapter.replaceAll(listOf(fill(1, side = "SELL"), fill(3)))

        assertEquals(2, adapter.itemCount)
        assertEquals("id-1", boundText(0))
        assertEquals("id-3", boundText(1))
    }

    private fun boundText(position: Int): String {
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, position)
        return holder.itemView.findViewById<TextView>(R.id.fillTime).text.toString()
    }

    private fun recordingObserver(events: MutableList<String>) = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            events.add("changed")
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            events.add("inserted:$positionStart:$itemCount")
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            events.add("removed:$positionStart:$itemCount")
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            events.add("changed:$positionStart:$itemCount")
        }

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
            events.add("moved:$fromPosition:$toPosition:$itemCount")
        }
    }

    private fun fill(id: Int, side: String = "BUY") = PrivateExecution(
        id.toLong(), side, BigDecimal.ONE, BigDecimal.ONE, "id-$id"
    )
}
