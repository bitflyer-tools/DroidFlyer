package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.domain.service.BoardSource
import com.unhappychoice.droidflyer.domain.service.BoardUpdate
import com.unhappychoice.droidflyer.domain.service.OrderBookEvent
import com.unhappychoice.droidflyer.domain.service.observeOrderBook
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import com.unhappychoice.droidflyer.presentation.adapter.activeOrder
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LimitOrderRowsTest {
    @Test
    fun boardAndGroupingChangesAreGroupedOffThreadAndLimitedToFiftyRowsPerSide() {
        val levels = (1L..60L).flatMap { bucket ->
            listOf(Order(bucket * 10, bucket.toDouble()), Order(bucket * 10 + 1, 0.5))
        }
        val boards = PublishSubject.create<Board>()
        val groupingSizes = BehaviorSubject.createDefault(1L)
        val scheduler = TestScheduler()
        val rows = observeLimitOrderRows(
            Observable.just(true), boards, groupingSizes, Observable.just(emptyList()), scheduler, scheduler
        ).map { it.first }.test()

        boards.onNext(Board(midPrice = 0.0, bids = levels.reversed(), asks = levels))
        rows.assertNoValues()

        scheduler.triggerActions()
        rows.assertValue(
            LimitOrderRows(
                asks = levels.take(50),
                bids = levels.reversed().take(50),
                groupingSize = 1L
            )
        )

        groupingSizes.onNext(10L)
        rows.assertValueCount(1)
        scheduler.triggerActions()
        rows.assertValueCount(2).assertValueAt(
            1,
            LimitOrderRows(
                asks = listOf(Order(10L, 1.0)) + (2L..50L).map { Order(it * 10, it + 0.5) },
                bids = (60L downTo 11L).map { Order(it * 10, it + 0.5) },
                groupingSize = 10L
            )
        )
    }

    @Test
    fun hiddenPagesSkipBoardUpdatesAndReselectionUsesLatestBoardAndGrouping() {
        val selected = BehaviorSubject.createDefault(false)
        val boards = BehaviorSubject.createDefault(boardAt(101L))
        val groupingSizes = BehaviorSubject.createDefault(1L)
        val preparation = TestScheduler()
        val render = TestScheduler()
        val orders = BehaviorSubject.createDefault(emptyList<ChildOrder>())
        val rows = observeLimitOrderRows(selected, boards, groupingSizes, orders, render, preparation).test()

        assertFalse(boards.hasObservers())
        assertFalse(orders.hasObservers())
        boards.onNext(boardAt(111L))
        preparation.triggerActions()
        render.triggerActions()
        rows.assertNoValues()

        selected.onNext(true)
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValue(LimitOrderRows(listOf(Order(111L, 1.0)), listOf(Order(111L, 1.0)), 1L) to emptyList())

        boards.onNext(boardAt(121L))
        preparation.triggerActions()
        orders.onNext(listOf(activeOrder("BUY", 120L, 0.5)))
        selected.onNext(false)
        assertFalse(boards.hasObservers())
        assertFalse(orders.hasObservers())

        groupingSizes.onNext(10L)
        boards.onNext(boardAt(131L))
        val latestOrders = listOf(activeOrder("BUY", 130L, 0.25))
        orders.onNext(latestOrders)
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValueCount(1)

        selected.onNext(true)
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValueCount(2).assertValueAt(
            1, LimitOrderRows(listOf(Order(140L, 1.0)), listOf(Order(130L, 1.0)), 10L) to latestOrders
        )

        boards.onNext(boardAt(151L))
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValueCount(3).assertValueAt(
            2, LimitOrderRows(listOf(Order(160L, 1.0)), listOf(Order(150L, 1.0)), 10L) to latestOrders
        )
        orders.onNext(emptyList())
        render.triggerActions()
        rows.assertValueCount(4).assertValueAt(3, rows.values()[2].first to emptyList())
    }

    @Test
    fun deepBookMatchesFullGroupingAndReadsOnlyThroughTheNextBucket() {
        val levels = (1L..500L).flatMap { bucket ->
            listOf(Order(bucket * 5000 + 1, 0.1), Order(bucket * 5000 + 2, 0.2))
        }
        val descending = levels.reversed()

        listOf(1L, 10L, 50L, 100L, 250L, 500L, 1000L, 2500L, 5000L).forEach { size ->
            val asks = CountingLevels(levels)
            val bids = CountingLevels(descending)
            val board = Board(0.0, bids, asks)
            val expected = fullyGrouped(board.copy(asks = levels, bids = descending), size)

            val scheduler = Schedulers.trampoline()
            observeLimitOrderRows(
                Observable.just(true), Observable.just(board), Observable.just(size),
                Observable.just(emptyList()), scheduler, scheduler
            ).map { it.first }
                .test().assertResult(expected)

            val expectedReads = if (size == 1L) 51 else 101
            assertEquals("Ask reads at grouping size $size", expectedReads, asks.reads)
            assertEquals("Bid reads at grouping size $size", expectedReads, bids.reads)
        }
    }

    @Test
    fun deletingTheBestBucketsPromotesDeeperBucketsOnBothSides() {
        val asks = (1L..1000L).map { Order(2000 + it, 0.5) }
        val bids = (1000L downTo 1L).map { Order(it, 0.25) }
        val updates = PublishSubject.create<BoardUpdate>()
        val scheduler = TestScheduler()
        val boards = observeOrderBook(updates, scheduler)
            .ofType(OrderBookEvent.View::class.java).map { it.board }
        val rows = observeLimitOrderRows(
            Observable.just(true), boards, Observable.just(10L),
            Observable.just(emptyList()), scheduler, scheduler
        ).map { it.first }.test()

        updates.onNext(BoardUpdate(Board(0.0, bids.reversed(), asks.reversed()), BoardSource.SNAPSHOT))
        scheduler.advanceTimeBy(300, MILLISECONDS)
        val initial = fullyGrouped(Board(0.0, bids, asks), 10L)
        rows.assertValue(initial)

        val delta = Board(
            midPrice = 0.0,
            bids = listOf(bids.first().copy(size = 0.0)),
            asks = asks.take(10).map { it.copy(size = 0.0) }
        )
        updates.onNext(BoardUpdate(delta, BoardSource.DELTA))
        scheduler.advanceTimeBy(300, MILLISECONDS)

        val promoted = fullyGrouped(Board(0.0, bids.drop(1), asks.drop(10)), 10L)
        rows.assertValues(initial, promoted)
        assertEquals(Order(2510, 5.0), promoted.asks.last())
        assertEquals(Order(500, 2.5), promoted.bids.last())
    }

    private fun fullyGrouped(board: Board, size: Long) = LimitOrderRows(
        asks = BoardType.Ask.groupBySize(board.asks.sortedBy { it.price }, size).take(50),
        bids = BoardType.Bid.groupBySize(board.bids.sortedByDescending { it.price }, size).take(50),
        groupingSize = size
    )

    private class CountingLevels(private val levels: List<Order>) : AbstractList<Order>() {
        override val size get() = levels.size
        var reads = 0
            private set

        override fun get(index: Int): Order {
            reads++
            return levels[index]
        }
    }

    private fun boardAt(price: Long) = Board(0.0, listOf(Order(price, 1.0)), listOf(Order(price, 1.0)))
}
