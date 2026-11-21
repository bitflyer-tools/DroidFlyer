package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import org.junit.Test

class BoardUpdatesTest {
    private val scheduler = TestScheduler()
    private val updates = PublishSubject.create<BoardUpdate>()
    private val events = observeOrderBook(updates, scheduler).test()

    @Test
    fun replaysDeepBookAndPublishesOnlyTheLatestViewEachInterval() {
        val bids = (1L..1000L).map { Order(it, 1.0) }
        val asks = (2000L downTo 1001L).map { Order(it, 1.0) }
        val initial = Board(1000.5, bids, asks)
        send(initial, BoardSource.SNAPSHOT)
        send(Board(0.0, listOf(Order(1000, 2.0)), emptyList()))
        events.assertNoValues()

        scheduler.advanceTimeBy(299, TimeUnit.MILLISECONDS)
        val initialQuotes = OrderBookEvent.Quotes(1001, 1000)
        events.assertValue(initialQuotes)

        scheduler.advanceTimeBy(1, TimeUnit.MILLISECONDS)
        val firstView = OrderBookEvent.View(
            Board(1000.5, listOf(Order(1000, 2.0)) + bids.dropLast(1).reversed(), asks.reversed())
        )
        events.assertValues(initialQuotes, firstView)

        send(Board(0.0, listOf(Order(1000, 0.0)), listOf(Order(1001, 0.0))))
        val reconnected = Board(990.0, listOf(Order(980, 1.0)), listOf(Order(1010, 1.0)))
        send(reconnected, BoardSource.SNAPSHOT)
        send(initial, BoardSource.REST)
        send(Board(0.0, listOf(Order(980, 0.0), Order(985, 2.0)), listOf(Order(1010, 3.0))))
        send(Board(0.0, listOf(Order(985, 4.0)), emptyList()))
        scheduler.advanceTimeBy(300, TimeUnit.MILLISECONDS)

        events.assertValues(
            initialQuotes,
            firstView,
            OrderBookEvent.Quotes(1002, 999),
            OrderBookEvent.Quotes(1010, 980),
            OrderBookEvent.Quotes(1010, 985),
            OrderBookEvent.View(Board(990.0, listOf(Order(985, 4.0)), listOf(Order(1010, 3.0))))
        )
        scheduler.advanceTimeBy(300, TimeUnit.MILLISECONDS)
        events.assertValueCount(6)
    }

    private fun send(board: Board, source: BoardSource = BoardSource.DELTA) {
        updates.onNext(BoardUpdate(board, source))
    }
}
