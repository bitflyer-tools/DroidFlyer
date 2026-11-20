package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.presentation.view.ChartView
import io.reactivex.Observable
import mortar.ViewPresenter
import java.util.concurrent.TimeUnit

class ChartPresenter(
    private val nowNanos: () -> Long,
    private val getExecutionPrices: (String, Int) -> Observable<List<ExecutionPrice>>
) : ViewPresenter<ChartView>() {
    fun cachedPrices(): List<Double>? = latestSeries
        ?.takeIf { nowNanos() - it.loadedAtNanos < FRESHNESS_NANOS }
        ?.prices

    fun loadPrices(): Observable<List<Double>> =
        Observable.defer { getExecutionPrices("FX_BTC_JPY", 500) }
            .map { executions ->
                val prices = executions.sortedBy { it.id }.map { it.price }
                require(prices.isNotEmpty() && prices.all { it.isFinite() && it > 0 })
                prices
            }
            .doOnNext { latestSeries = CachedSeries(it, nowNanos()) }

    @Volatile
    private var latestSeries: CachedSeries? = null

    private data class CachedSeries(val prices: List<Double>, val loadedAtNanos: Long)

    private companion object {
        val FRESHNESS_NANOS = TimeUnit.SECONDS.toNanos(30)
    }
}
