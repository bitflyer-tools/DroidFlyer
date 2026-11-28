package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class RealtimeSession(
    private val client: RealtimeClient,
    private val status: CurrentStatusService,
    private val loadAccount: (APITokenPreference.Snapshot) -> Pair<APIClientV1, StopSubmissionGuard>?
) {
    private val subscriptions = CompositeDisposable()

    init {
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

    fun notifyExecutions(onExecution: (ChildOrderEvent) -> Unit) {
        client.childOrderEvents
            .subscribeOnIoObserveOnUI()
            .filter { it.isCurrent() }
            .subscribeNext { batch -> batch.value.filter(ChildOrderEvent::isExecution).forEach(onExecution) }
            .addTo(subscriptions)
    }

    fun close() {
        subscriptions.dispose()
        status.dispose()
        client.disconnect()
    }
}
