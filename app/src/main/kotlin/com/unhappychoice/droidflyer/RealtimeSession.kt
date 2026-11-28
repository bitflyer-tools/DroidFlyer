package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo

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
