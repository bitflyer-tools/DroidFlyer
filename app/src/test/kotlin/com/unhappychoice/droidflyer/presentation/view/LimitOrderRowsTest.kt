package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
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
        val rows = observeLimitOrderRows(boards, groupingSizes, scheduler).test()

        boards.onNext(Board(0.0, levels, levels.reversed()))
        scheduler.advanceTimeBy(299, MILLISECONDS)
        rows.assertNoValues()

        scheduler.advanceTimeBy(1, MILLISECONDS)
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
}
