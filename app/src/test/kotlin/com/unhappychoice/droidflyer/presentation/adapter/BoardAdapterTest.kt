package com.unhappychoice.droidflyer.presentation.adapter

import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.view.observeLimitOrderRows
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BoardAdapterTest {
    @Test
    fun clickingAskRowsEmitsTheirGroupedPricesForSellConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Ask)
        val exactAsk = Order(100L, 1.0)
        val roundedAsk = Order(110L, 2.0)
        adapter.applyRows(listOf(exactAsk, roundedAsk), groupingSize = 10L, myOrders = emptyList())

        val holder = createHolder(adapter)
        val selectedAsks = adapter.clickItems.test()

        adapter.onBindViewHolder(holder, 0)
        holder.view.performClick()
        adapter.onBindViewHolder(holder, 1)
        holder.view.performClick()

        selectedAsks.assertValues(exactAsk, roundedAsk)
    }

    @Test
    fun onlyChangedVisibleBidNotifiesAcrossBothSides() {
        val context = RuntimeEnvironment.getApplication()
        val asks = BoardAdapter(context, BoardType.Ask)
        val bids = BoardAdapter(context, BoardType.Bid)
        val boards = PublishSubject.create<Board>()
        val scheduler = TestScheduler()
        observeLimitOrderRows(
            Observable.just(true), boards, Observable.just(1L), Observable.just(emptyList()), scheduler, scheduler
        ).subscribe { (rows, orders) ->
            asks.applyRows(rows.asks, rows.groupingSize, orders)
            bids.applyRows(rows.bids, rows.groupingSize, orders)
        }
        val board = Board(0.0, bids = (100L downTo 50L).map { Order(it, 1.0) }, asks = listOf(Order(200, 1.0)))
        boards.onNext(board)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        val askChanges = asks.recordChanges()
        val bidChanges = bids.recordChanges()

        val outsideDepth = board.copy(bids = board.bids.dropLast(1) + Order(50, 2.0))
        boards.onNext(outsideDepth)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertEquals(emptyList<String>(), askChanges)
        assertEquals(emptyList<String>(), bidChanges)

        boards.onNext(outsideDepth.copy(bids = listOf(Order(100, 3.0)) + outsideDepth.bids.drop(1)))
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertEquals(emptyList<String>(), askChanges)
        assertEquals(listOf("change 0 1"), bidChanges)
    }

    @Test
    fun groupingAndPersonalOrderChangesUpdateTheDisplayedQuantity() {
        val adapter = BoardAdapter(RuntimeEnvironment.getApplication(), BoardType.Bid)
        val orders = listOf(Order(100, 1.0), Order(90, 2.0))
        val personal = personalBid()
        adapter.applyRows(orders, 1, listOf(personal))
        val holder = createHolder(adapter)
        val mineSize = holder.view.findViewById<TextView>(R.id.mineSize)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, mineSize.visibility)
        val changes = adapter.recordChanges()

        adapter.applyRows(orders, 2, emptyList())
        assertEquals(listOf("change 0 2"), changes)
        changes.clear()

        adapter.applyRows(orders, 10, listOf(personal))
        assertEquals(listOf("change 0 2"), changes)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, mineSize.visibility)
        assertEquals("0.1", mineSize.text.toString())
        changes.clear()

        adapter.applyRows(orders, 10, listOf(personal.copy(outstandingSize = 0.04, executedSize = 0.06)))
        assertEquals(listOf("change 0 1"), changes)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("0.04", mineSize.text.toString())
        changes.clear()

        adapter.applyRows(orders, 10, emptyList())
        assertEquals(listOf("change 0 1"), changes)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, mineSize.visibility)
        assertEquals("", mineSize.text.toString())
    }

    @Test
    fun personalQuantityTracksOrderChangesAndRegroupingAcrossBoardUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Ask)
        val holder = createHolder(adapter)
        val row = holder.view
        val personalSize = row.findViewById<TextView>(R.id.mineSize)
        val order = personalBid().copy(side = "SELL", outstandingSize = 0.08)
        adapter.applyRows(listOf(Order(110L, 1.0)), groupingSize = 10L, myOrders = listOf(order))

        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, personalSize.visibility)
        assertEquals("0.08", personalSize.text.toString())

        val partiallyFilled = listOf(order.copy(outstandingSize = 0.03))
        adapter.applyRows(listOf(Order(110L, 2.0)), groupingSize = 10L, myOrders = partiallyFilled)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("0.03", personalSize.text.toString())

        adapter.applyRows(listOf(Order(101L, 1.0), Order(103L, 2.0)), groupingSize = 1L, myOrders = partiallyFilled)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, personalSize.visibility)
        assertEquals("0.03", personalSize.text.toString())
        adapter.onBindViewHolder(holder, 1)
        assertEquals(View.GONE, personalSize.visibility)

        adapter.applyRows(listOf(Order(110L, 2.0)), groupingSize = 10L, myOrders = partiallyFilled)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, personalSize.visibility)
        assertEquals("0.03", personalSize.text.toString())

        adapter.applyRows(listOf(Order(110L, 2.0)), groupingSize = 10L, myOrders = emptyList())
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, personalSize.visibility)
        assertEquals("", personalSize.text.toString())
    }

    @Test
    fun publicBoardUpdatesReusePersonalQuantitiesWithoutReadingOrders() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = BoardAdapter(context, BoardType.Bid)
        val holder = createHolder(adapter)
        val row = holder.view
        var orderReads = 0
        val personalOrders = object : AbstractList<ChildOrder>() {
            override val size = 1

            override fun get(index: Int): ChildOrder {
                orderReads++
                return personalBid().copy(price = 100, outstandingSize = 0.04)
            }
        }
        adapter.applyRows(listOf(Order(100L, 1.0)), groupingSize = 1L, myOrders = personalOrders)
        val indexedOrderReads = orderReads

        listOf(2.0, 3.0).forEach { size ->
            adapter.applyRows(listOf(Order(100L, size)), groupingSize = 1L, myOrders = personalOrders)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("0.04", row.findViewById<TextView>(R.id.mineSize).text.toString())
        }
        assertEquals(indexedOrderReads, orderReads)
    }

    private fun BoardAdapter.recordChanges() = mutableListOf<String>().also { changes ->
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

    private fun createHolder(adapter: BoardAdapter): BoardAdapter.ViewHolder {
        val context = adapter.context
        return adapter.ViewHolder(RelativeLayout(context).apply {
            listOf(R.id.price, R.id.size, R.id.mineSize).forEach { id ->
                addView(TextView(context).apply { this.id = id })
            }
            addView(View(context).apply { id = R.id.backgroundView })
        })
    }

    private fun personalBid() = ChildOrder(
        id = 1,
        childOrderId = "order",
        productCode = "BTC_JPY",
        side = "BUY",
        childOrderType = "LIMIT",
        price = 101,
        average_price = 101,
        size = 0.1,
        childOrderState = "ACTIVE",
        childOrderAcceptanceId = "acceptance",
        outstandingSize = 0.1,
        cancelSize = 0.0,
        executedSize = 0.0,
        totalCommission = 0.0
    )
}
