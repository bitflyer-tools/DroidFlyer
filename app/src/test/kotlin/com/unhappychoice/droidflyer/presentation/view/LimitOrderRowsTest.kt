package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.adapter.activeOrder
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit.MILLISECONDS

class LimitOrderRowsTest {
    @Test
    fun sampledBoardAndGroupingChangesPreserveTheFirstFiftyRowsPerSide() {
        val levels = (1L..60L).flatMap { bucket ->
            listOf(Order(bucket * 10, bucket.toDouble()), Order(bucket * 10 + 1, 0.5))
        }
        val boards = PublishSubject.create<Board>()
        val groupingSizes = BehaviorSubject.createDefault(1L)
        val scheduler = TestScheduler()
        val rows = observeLimitOrderRows(
            Observable.just(true), boards, groupingSizes, Observable.just(emptyList()), scheduler, scheduler
        ).map { it.first }.test()

        boards.onNext(Board(0.0, emptyList(), emptyList()))
        boards.onNext(Board(0.0, levels, levels.reversed()))
        scheduler.advanceTimeBy(299, MILLISECONDS)
        rows.assertValue(LimitOrderRows(emptyList(), emptyList(), 1L))

        scheduler.advanceTimeBy(1, MILLISECONDS)
        rows.assertValueCount(2).assertValueAt(
            1,
            LimitOrderRows(
                asks = levels.take(50),
                bids = levels.reversed().take(50),
                groupingSize = 1L
            )
        )

        groupingSizes.onNext(10L)
        rows.assertValueCount(2)
        scheduler.triggerActions()
        rows.assertValueCount(3).assertValueAt(
            2,
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
        preparation.advanceTimeBy(900, MILLISECONDS)
        render.triggerActions()
        rows.assertNoValues()

        selected.onNext(true)
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValue(LimitOrderRows(listOf(Order(111L, 1.0)), listOf(Order(111L, 1.0)), 1L) to emptyList())

        boards.onNext(boardAt(121L))
        preparation.advanceTimeBy(300, MILLISECONDS)
        orders.onNext(listOf(activeOrder("BUY", 120L, 0.5)))
        selected.onNext(false)
        assertFalse(boards.hasObservers())
        assertFalse(orders.hasObservers())

        groupingSizes.onNext(10L)
        boards.onNext(boardAt(131L))
        val latestOrders = listOf(activeOrder("BUY", 130L, 0.25))
        orders.onNext(latestOrders)
        preparation.advanceTimeBy(900, MILLISECONDS)
        render.triggerActions()
        rows.assertValueCount(1)

        selected.onNext(true)
        preparation.triggerActions()
        render.triggerActions()
        rows.assertValueCount(2).assertValueAt(
            1, LimitOrderRows(listOf(Order(140L, 1.0)), listOf(Order(130L, 1.0)), 10L) to latestOrders
        )

        boards.onNext(boardAt(151L))
        preparation.advanceTimeBy(300, MILLISECONDS)
        render.triggerActions()
        rows.assertValueCount(3).assertValueAt(
            2, LimitOrderRows(listOf(Order(160L, 1.0)), listOf(Order(150L, 1.0)), 10L) to latestOrders
        )
        orders.onNext(emptyList())
        render.triggerActions()
        rows.assertValueCount(4).assertValueAt(3, rows.values()[2].first to emptyList())
    }

    private fun boardAt(price: Long) = Board(0.0, listOf(Order(price, 1.0)), listOf(Order(price, 1.0)))
}
