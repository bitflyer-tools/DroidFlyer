package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

internal class RealtimeSession(
    private val client: RealtimeClient,
    private val status: CurrentStatusService
) {
    private val subscriptions = CompositeDisposable()

    init {
        client.preference.accountUnlinked
            .subscribeNext {
                client.updateAccount {}
                status.switchAccount(null)
            }
            .addTo(subscriptions)
    }

    fun watchSavedAccount(loadAccount: (APICredentials) -> Pair<APIClientV1?, StopSubmissionGuard>) {
        client.preference.credentialsChanged
            .mergeWith(Observable.just(Unit))
            .observeOn(Schedulers.io())
            .map { client.refreshSavedAccount() }
            .distinctUntilChanged { previous, current ->
                previous.key == current.key && previous.secret == current.secret
            }
            .map { loadAccount(it) }
            .observeOn(androidMainThreadScheduler())
            .subscribeNext { (api, guard) -> status.switchAccount(api, guard) }
            .addTo(subscriptions)
    }

    fun notifyExecutions(onExecution: (ChildOrderEvent) -> Unit) =
        notifyChildOrders(ChildOrderEvent::isExecution, onExecution)

    fun notifyOrderOutcomes(onOutcome: (ChildOrderEvent) -> Unit) =
        notifyChildOrders(ChildOrderEvent::isNotificationOutcome, onOutcome)

    fun close() {
        subscriptions.dispose()
        status.dispose()
        client.disconnect()
    }

    private fun notifyChildOrders(
        matches: (ChildOrderEvent) -> Boolean,
        onEvent: (ChildOrderEvent) -> Unit
    ) {
        client.childOrderEvents
            .subscribeOnIoObserveOnUI()
            .filter { it.isCurrent() }
            .subscribeNext { batch -> batch.value.filter(matches).forEach(onEvent) }
            .addTo(subscriptions)
    }
}
