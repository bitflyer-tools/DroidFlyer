package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.presentation.view.ChartView
import io.reactivex.Observable
import mortar.ViewPresenter
import java.util.concurrent.TimeUnit

class ChartPresenter(
    private val nowNanos: () -> Long,
    private val getExecutionPrices: (String, Int, Long?) -> Observable<List<ExecutionPrice>>
) : ViewPresenter<ChartView>() {
    var interval = CandleInterval.ONE_MINUTE

    fun cachedHistory(): CandleHistory? = latestHistory
        ?.takeIf { nowNanos() - it.loadedAtNanos < FRESHNESS_NANOS }
        ?.history

    fun loadHistory(): Observable<CandleHistory> = Observable.defer {
        loadPage(mutableMapOf(), before = null, remaining = MAX_PAGES)
    }.doOnNext { latestHistory = CachedHistory(it, nowNanos()) }

    @Volatile
    private var latestHistory: CachedHistory? = null

    private fun loadPage(
        trades: MutableMap<Long, ChartTrade>,
        before: Long?,
        remaining: Int
    ): Observable<CandleHistory> = getExecutionPrices("FX_BTC_JPY", PAGE_SIZE, before).flatMap { page ->
        val older = page.filter { before == null || it.id < before }
        ChartTrade.parsePage(older.distinctBy { it.id }).forEach { trades[it.id] = it }
        require(trades.isNotEmpty())

        val newest = trades.values.maxOf { it.time }
        val windowStart = CandleInterval.FIVE_MINUTES.startOf(newest) + CandleInterval.FIVE_MINUTES.millis - HISTORY_MILLIS
        val covered = page.isEmpty() || trades.values.any { it.time < windowStart }
        val nextCursor = older.minOfOrNull { it.id }
        if (!covered && remaining > 1 && nextCursor != null) {
            loadPage(trades, nextCursor, remaining - 1)
        } else {
            val visible = trades.values.filter { it.time >= windowStart }
                .sortedWith(compareBy(ChartTrade::time, ChartTrade::nanos, ChartTrade::id))
            Observable.just(CandleHistory(visible, windowStart, limited = !covered))
        }
    }

    private data class CachedHistory(val history: CandleHistory, val loadedAtNanos: Long)

    private companion object {
        const val PAGE_SIZE = 500
        const val MAX_PAGES = 5
        val HISTORY_MILLIS = TimeUnit.MINUTES.toMillis(30)
        val FRESHNESS_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
