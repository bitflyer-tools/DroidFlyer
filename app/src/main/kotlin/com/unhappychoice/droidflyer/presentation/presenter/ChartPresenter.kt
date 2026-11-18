package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import com.unhappychoice.droidflyer.presentation.view.ChartView
import io.reactivex.Observable
import mortar.ViewPresenter

class ChartPresenter(
    private val getExecutionPrices: (String, Int) -> Observable<List<ExecutionPrice>>
) : ViewPresenter<ChartView>() {
    fun loadPrices(): Observable<List<Double>> =
        Observable.defer { getExecutionPrices("FX_BTC_JPY", 500) }
            .map { executions ->
                val prices = executions.sortedBy { it.id }.map { it.price }
                require(prices.isNotEmpty() && prices.all { it.isFinite() && it > 0 })
                prices
            }
}
