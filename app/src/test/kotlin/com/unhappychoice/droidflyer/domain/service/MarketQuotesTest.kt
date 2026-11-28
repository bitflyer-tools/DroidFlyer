package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import org.junit.Test

class MarketQuotesTest {
    @Test
    fun quotesTrackBootstrapAndDeletionsWithoutEmittingForSizeChanges() {
        val updates = PublishSubject.create<BoardUpdate>()
        val scheduler = TestScheduler()
        val quotes = observeOrderBook(updates, scheduler).ofType(OrderBookEvent.Quotes::class.java).test()

        updates.onNext(BoardUpdate(Board(100.0, listOf(Order(99, 1.0)), listOf(Order(101, 1.0))), BoardSource.REST))
        updates.onNext(BoardUpdate(Board(0.0, listOf(Order(99, 2.0)), emptyList()), BoardSource.DELTA))
        updates.onNext(BoardUpdate(Board(0.0, emptyList(), listOf(Order(101, 0.0))), BoardSource.DELTA))
        updates.onNext(BoardUpdate(Board(0.0, listOf(Order(99, 0.0)), emptyList()), BoardSource.DELTA))
        scheduler.triggerActions()

        quotes.assertValues(OrderBookEvent.Quotes(101, 99), OrderBookEvent.Quotes(0, 99), OrderBookEvent.Quotes(0, 0))
    }
}
