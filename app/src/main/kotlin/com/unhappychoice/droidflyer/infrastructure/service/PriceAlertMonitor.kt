package com.unhappychoice.droidflyer.infrastructure.service

import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.triggeredAlerts
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class PriceAlertMonitor(
    private val preference: PriceAlertPreference,
    prices: Observable<Double>,
    private val onTriggered: (PriceAlert) -> Unit
) {
    private val bag = CompositeDisposable()
    private var previousPrice: Double? = null

    init {
        prices.subscribeNext { price ->
            triggeredAlerts(previousPrice, price, preference.read()).forEach { alert ->
                preference.remove(alert.id)
                onTriggered(alert)
            }
            previousPrice = price
        }.addTo(bag)
    }

    fun dispose() = bag.dispose()
}
