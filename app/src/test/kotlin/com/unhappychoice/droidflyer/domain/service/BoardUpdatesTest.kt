package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.Test

class BoardUpdatesTest {
    @Test
    fun reconnectSnapshotReplacesTheBookBeforeSubsequentDeltas() {
        val snapshots = PublishSubject.create<Board>()
        val deltas = PublishSubject.create<Board>()
        val scheduler = TestScheduler()
        val initial = Board(100.0, listOf(Order(99, 1.0)), listOf(Order(101, 1.0)))
        val reconnected = Board(100.0, listOf(Order(98, 1.0)), listOf(Order(102, 1.0)))
        val updates = boardUpdates(snapshots, deltas)
            .observeOn(scheduler)
            .scan(initial) { book, update -> update(book) }
            .test()

        snapshots.onNext(reconnected)
        deltas.onNext(Board(100.0, listOf(Order(99, 2.0)), listOf(Order(102, 0.0), Order(103, 2.0))))
        scheduler.triggerActions()
        updates.assertValues(
            initial,
            reconnected,
            Board(100.0, listOf(Order(98, 1.0), Order(99, 2.0)), listOf(Order(103, 2.0)))
        )
    }
}
