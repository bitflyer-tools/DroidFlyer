package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class RealtimeSession(
    private val client: RealtimeClient,
    private val status: CurrentStatusService
) {
    private val subscriptions = CompositeDisposable()

    init {
        client.preference.accountUnlinked
            .subscribeNext {
                status.unlinkAccount()
                client.restartConnection()
            }
            .addTo(subscriptions)
    }

    fun notifyExecutions(onExecution: (ChildOrderEvent) -> Unit) {
        client.childOrderEvents
            .subscribeNext { events -> events.filter(ChildOrderEvent::isExecution).forEach(onExecution) }
            .addTo(subscriptions)
    }

    fun close() {
        subscriptions.dispose()
        status.dispose()
        client.disconnect()
    }
}
