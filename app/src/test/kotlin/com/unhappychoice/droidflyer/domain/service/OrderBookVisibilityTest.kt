package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.Test

class OrderBookVisibilityTest {
    private val scheduler = TestScheduler()
    private val updates = PublishSubject.create<BoardUpdate>()
    private val visibility = PublishSubject.create<Boolean>()
    private val events = observeOrderBook(updates, scheduler, visible = visibility.startWithItem(false)).test()

    @Test
    fun hiddenLimitSkipsSnapshotsButQuotesTrackDeltasAndDisconnects() {
        val bids = (1L..1000L).map { Order(it, 1.0) }
        val asks = (2000L downTo 1001L).map { Order(it, 1.0) }
        send(Board(1000.5, bids, asks), BoardSource.SNAPSHOT)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        events.assertValue(OrderBookEvent.Quotes(1001, 1000))

        send(Board(0.0, listOf(Order(1000, 2.0)), emptyList()))
        scheduler.advanceTimeBy(600, MILLISECONDS)
        events.assertValueCount(1)

        updates.onNext(BoardUpdate(Board(0.0, emptyList(), emptyList()), BoardSource.DISCONNECT))
        scheduler.triggerActions()
        events.assertValues(OrderBookEvent.Quotes(1001, 1000), OrderBookEvent.Quotes(0, 0))

        visibility.onNext(true)
        scheduler.triggerActions()
        events.assertValueCount(3)
        events.assertValueAt(2, OrderBookEvent.View(Board(0.0, emptyList(), emptyList())))

        send(Board(0.0, listOf(Order(5, 1.0)), listOf(Order(10, 1.0))), BoardSource.SNAPSHOT)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        events.assertValueCount(5)
        events.assertValueAt(3, OrderBookEvent.Quotes(10, 5))
        events.assertValueAt(4, OrderBookEvent.View(Board(0.0, listOf(Order(5, 1.0)), listOf(Order(10, 1.0)))))
    }

    @Test
    fun reselectingLimitAfterHidingAgainResumesSampling() {
        send(Board(100.0, listOf(Order(99, 1.0)), listOf(Order(101, 1.0))), BoardSource.SNAPSHOT)
        visibility.onNext(true)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        events.assertValueAt(1, OrderBookEvent.View(Board(100.0, listOf(Order(99, 1.0)), listOf(Order(101, 1.0)))))

        visibility.onNext(false)
        send(Board(0.0, listOf(Order(98, 1.0)), emptyList()))
        scheduler.advanceTimeBy(600, MILLISECONDS)
        events.assertValueCount(2)

        visibility.onNext(true)
        scheduler.triggerActions()
        events.assertValueAt(
            2,
            OrderBookEvent.View(Board(100.0, listOf(Order(99, 1.0), Order(98, 1.0)), listOf(Order(101, 1.0))))
        )

        send(Board(0.0, listOf(Order(97, 1.0)), emptyList()))
        scheduler.advanceTimeBy(300, MILLISECONDS)
        events.assertValueCount(4)
        events.assertValueAt(
            3,
            OrderBookEvent.View(
                Board(100.0, listOf(Order(99, 1.0), Order(98, 1.0), Order(97, 1.0)), listOf(Order(101, 1.0)))
            )
        )
    }

    private fun send(board: Board, source: BoardSource = BoardSource.DELTA) {
        updates.onNext(BoardUpdate(board, source))
    }
}
