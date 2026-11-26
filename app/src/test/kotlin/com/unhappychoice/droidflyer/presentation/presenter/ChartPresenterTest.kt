package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChartPresenterTest {
    @Test
    fun aggregatesBothIntervalsWithTimeOrderedPricesAndGapsAcrossPages() {
        val cursors = mutableListOf<Long?>()
        val presenter = ChartPresenter({ 0L }) { productCode, count, before ->
            assertEquals("FX_BTC_JPY", productCode)
            assertEquals(500, count)
            cursors.add(before)
            Observable.just(when (before) {
                null -> listOf(trade(8, 300.0, "06:01"), trade(5, 260.0, "04:00"), trade(4, 240.0, "03:02.0000002"))
                4L -> listOf(
                    trade(4, 999.0, "03:02.0000002"), trade(1, 200.0, "03:02.0000003"),
                    trade(2, 220.0, "03:01"), trade(3, 250.0, "03:02.0000002")
                )
                else -> emptyList()
            })
        }

        val history = presenter.loadHistory().blockingSingle()
        assertEquals(listOf(null, 4L, 1L), cursors)
        val minutes = history.series(CandleInterval.ONE_MINUTE)
        val fiveMinutes = history.series(CandleInterval.FIVE_MINUTES)
        assertEquals(3, minutes.candles.size)
        assertEquals(180_000L, minutes.candles.last().start - minutes.candles.first().start)
        val first = minutes.candles.first()
        assertEquals(Candle(first.start, 220.0, 250.0, 200.0, 200.0, false), first)
        assertEquals(2, fiveMinutes.candles.size)
        assertEquals(Candle(first.start - 180_000L, 220.0, 260.0, 200.0, 260.0, false), fiveMinutes.candles.first())
    }

    @Test
    fun stopsAtFivePagesAndMarksOnlyTheOldestCandlePartial() {
        val cursors = mutableListOf<Long?>()
        val presenter = ChartPresenter({ 0L }) { _, _, before ->
            cursors.add(before)
            val id = (before ?: 10L) - 1
            Observable.just(listOf(trade(id, 100.0, if (id == 9L) "06:00" else "03:01")))
        }

        val history = presenter.loadHistory().blockingSingle()
        assertEquals(listOf(null, 9L, 8L, 7L, 6L), cursors)
        CandleInterval.entries.forEach { interval ->
            val series = history.series(interval)
            assertEquals(listOf(true, false), series.candles.map { it.partial })
            assertEquals(series.candles.first().start, series.start)
        }
    }

    @Test
    fun stopsWhenAnOverlappingPageCannotAdvanceTheCursor() {
        val cursors = mutableListOf<Long?>()
        val presenter = ChartPresenter({ 0L }) { _, _, before ->
            cursors.add(before)
            Observable.just(listOf(trade(1, 100.0)))
        }

        val candle = presenter.loadHistory().blockingSingle().series(CandleInterval.ONE_MINUTE).candles.single()
        assertEquals(listOf(null, 1L), cursors)
        assertTrue(candle.partial)
    }

    @Test
    fun stopsWhenARequestCoversTheTargetWindow() {
        val presenter = ChartPresenter({ 0L }) { _, _, before ->
            assertNull(before)
            Observable.just(listOf(trade(2, 200.0, "06:00"), trade(1, 100.0, "00:00", hour = "00")))
        }

        val series = presenter.loadHistory().blockingSingle().series(CandleInterval.FIVE_MINUTES)
        assertEquals(30 * 60_000L, series.end - series.start)
        assertEquals(200.0, series.candles.single().open, 0.0)
        assertFalse(series.candles.single().partial)
    }

    @Test
    fun retryRestartsPaginationAfterFailure() {
        val cursors = mutableListOf<Long?>()
        val presenter = ChartPresenter({ 0L }) { _, _, before ->
            cursors.add(before)
            when {
                before == null -> Observable.just(listOf(trade(1, 100.0)))
                cursors.size == 2 -> Observable.error(IOException("Offline"))
                else -> Observable.just(emptyList())
            }
        }

        presenter.loadHistory().test().assertFailure(IOException::class.java)
        assertNull(presenter.cachedHistory())
        presenter.loadHistory().test().assertValueCount(1).assertComplete()
        assertEquals(listOf(null, 1L, null, 1L), cursors)
    }

    @Test
    fun cachedHistoryExpiresThirtySecondsAfterTheSuccessfulResponse() {
        var nowNanos = 0L
        val executions = PublishSubject.create<List<ExecutionPrice>>()
        val presenter = ChartPresenter({ nowNanos }) { _, _, before ->
            if (before == null) executions else Observable.just(emptyList())
        }
        val load = presenter.loadHistory().test()

        nowNanos = TimeUnit.SECONDS.toNanos(40)
        executions.onNext(listOf(trade(1, 100.0)))
        executions.onComplete()
        load.assertValueCount(1).assertComplete()
        nowNanos = TimeUnit.SECONDS.toNanos(70) - 1
        assertSame(load.values().single(), presenter.cachedHistory())
        nowNanos++
        assertNull(presenter.cachedHistory())
    }

    @Test
    fun parsesExecutionTimestampsWithZOrUtcOffsetSuffixes() {
        val presenter = ChartPresenter({ 0L }) { _, _, _ ->
            Observable.just(
                listOf(
                    ExecutionPrice(1, 100.0, "2025-01-01T00:00:00Z"),
                    ExecutionPrice(2, 200.0, "2025-01-01T00:00:01.5+00:00")
                )
            )
        }

        val candle = presenter.loadHistory().blockingSingle().series(CandleInterval.ONE_MINUTE).candles.single()
        assertEquals(100.0, candle.open, 0.0)
        assertEquals(200.0, candle.close, 0.0)
    }

    @Test
    fun rejectsEmptyHistoryAndInvalidExecutions() {
        listOf(emptyList(), listOf(trade(1, 0.0)), listOf(ExecutionPrice(1, 100.0, null))).forEach { page ->
            val presenter = ChartPresenter({ 0L }) { _, _, _ -> Observable.just(page) }

            presenter.loadHistory().test().assertFailure(IllegalArgumentException::class.java)
            assertNull(presenter.cachedHistory())
        }
    }

    private fun trade(id: Long, price: Double, time: String = "00:00", hour: String = "01") =
        ExecutionPrice(id, price, "2025-01-01T$hour:$time")
}
