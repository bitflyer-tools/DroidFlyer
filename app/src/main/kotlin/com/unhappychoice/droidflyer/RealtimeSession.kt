package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import io.reactivex.rxjava3.schedulers.Schedulers

internal class RealtimeSession(
    private val client: RealtimeClient,
    private val status: CurrentStatusService? = null,
    private val loadAccount: (APITokenPreference.Snapshot) -> Pair<APIClientV1, StopSubmissionGuard>? = { null }
) {
    val privateChannelAvailability = client.privateChannelAvailability
    private val subscriptions = CompositeDisposable()

    init {
        if (status != null) watchTradingAccount(status)
    }

    fun watchSavedAccount() {
        client.preference.savedCredentialsChanged
            .startWithItem(Unit)
            .observeOn(Schedulers.io())
            .subscribeNext { client.refreshSavedAccount() }
            .addTo(subscriptions)
    }

    fun notifyExecutions(onExecution: (ChildOrderEvent) -> Unit) =
        notifyChildOrders(ChildOrderEvent::isExecution, onExecution)

    fun notifyOrderOutcomes(onOutcome: (ChildOrderEvent) -> Unit) =
        notifyChildOrders(ChildOrderEvent::isNotificationOutcome, onOutcome)

    fun notifyParentOrderOutcomes(onOutcome: (ParentOrderEvent) -> Unit) {
        client.parentOrderEvents
            .observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .subscribeNext { batch ->
                batch.value.filter { it.eventType == "CANCEL" || it.eventType == "ORDER_FAILED" }.forEach(onOutcome)
            }
            .addTo(subscriptions)
    }

    fun close() {
        subscriptions.dispose()
        status?.dispose()
        client.disconnect()
    }

    private fun watchTradingAccount(status: CurrentStatusService) {
        // Every instance shares the preference, so an unlink or a validated replacement
        // from any instance reconnects this instance's realtime socket and trading session too.
        client.preference.accountChanged
            .subscribeNext {
                client.updateAccount {}
                status.applyAccountChange(client.preference, loadAccount)
            }
            .addTo(subscriptions)
        if (!status.privateState.hasPrivateClient) status.initializeAccount(client.preference, loadAccount)
    }

    private fun notifyChildOrders(
        matches: (ChildOrderEvent) -> Boolean,
        onEvent: (ChildOrderEvent) -> Unit
    ) {
        client.childOrderEvents
            .observeOn(androidMainThreadScheduler())
            .filter { it.isCurrent() }
            .subscribeNext { batch -> batch.value.filter(matches).forEach(onEvent) }
            .addTo(subscriptions)
    }
}
