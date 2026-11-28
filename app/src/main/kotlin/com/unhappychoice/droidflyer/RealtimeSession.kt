package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

internal class RealtimeSession(
    private val client: RealtimeClient,
    private val status: CurrentStatusService? = null
) {
    val privateChannelAvailability = client.privateChannelAvailability
    private val subscriptions = CompositeDisposable()

    init {
        client.preference.accountUnlinked
            .subscribeNext {
                client.updateAccount {}
                status?.switchAccount(null)
            }
            .addTo(subscriptions)
    }

    fun watchSavedAccount() {
        client.preference.credentialsChanged
            .startWith(Unit)
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
