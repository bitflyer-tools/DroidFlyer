package com.unhappychoice.droidflyer.infrastructure.service

import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.triggeredAlerts
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.CryptoCfdExecutionFeed.Event
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class PriceAlertMonitor(
    private val preference: PriceAlertPreference,
    events: Observable<Event>,
    private val postNotification: (PriceAlert) -> Boolean
) {
    private val bag = CompositeDisposable()
    private var previousPrice: Double? = null

    init {
        events.subscribeNext { event ->
            when (event) {
                Event.Disconnected -> previousPrice = null
                is Event.Executions -> event.batch.sortedBy { it.id }
                    .asSequence()
                    .map { it.price }
                    .filter { it.isFinite() && it > 0.0 }
                    .forEach(::evaluatePrice)
            }
        }.addTo(bag)
    }

    fun dispose() = bag.dispose()

    private fun evaluatePrice(price: Double) {
        triggeredAlerts(previousPrice, price, preference.read()).forEach { alert ->
            if (postNotification(alert)) preference.remove(alert.id)
        }
        previousPrice = price
    }
}
