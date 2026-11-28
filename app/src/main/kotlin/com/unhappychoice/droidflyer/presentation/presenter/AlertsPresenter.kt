package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import com.unhappychoice.droidflyer.presentation.view.AlertsView
import io.reactivex.rxjava3.core.Observable
import mortar.ViewPresenter

class AlertsPresenter internal constructor(
    private val preference: PriceAlertPreference,
    private val startMonitoring: () -> Unit
) : ViewPresenter<AlertsView>() {
    internal val alerts: Observable<List<PriceAlert>> =
        preference.alertsChanged.startWith(Observable.fromCallable { preference.read() })
    internal val maximumAlerts = PriceAlertPreference.MAX_ALERTS

    internal fun addAlert(targetPrice: Long, direction: PriceAlertDirection): Boolean {
        val added = preference.add(targetPrice, direction) != null
        if (added) startMonitoring()
        return added
    }

    internal fun removeAlert(id: Long) = preference.remove(id)
}
