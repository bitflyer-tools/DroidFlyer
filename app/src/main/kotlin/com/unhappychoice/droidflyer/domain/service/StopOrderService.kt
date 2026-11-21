package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.SerialDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.CompletableSubject

internal class StopOrderService(client: APIClientV1, private val io: Scheduler, private val main: Scheduler) {
    val orders = Variable<List<StopOrder>>(emptyList())
    val loadFailed = Variable(false)
    val submitting = Variable(false)
    val cancelling = Variable<Set<String>>(emptySet())

    private var account = Account(client)
    private val requests = CompositeDisposable()
    private var enabled = false
    private var recoverOnRefresh = false
    private var realtimeDisconnected = false
    private val refreshRequest = SerialDisposable().apply { addTo(requests) }
    private val events = LinkedHashMap<String, ParentOrderEvent>()

    fun switchAccount(client: APIClientV1) {
        val previous = account
        account = Account(client)
        previous.ended.onComplete()
        refreshRequest.set(null)
        events.clear()
        orders.value = emptyList()
        loadFailed.value = false
        if (enabled) refresh(recoverTriggers = true)
    }

    fun dispose() {
        account.ended.onComplete()
        requests.dispose()
    }

    fun confirmation(order: StopOrderRequest): Confirmation = Confirmation(account, order)

    fun submit(confirmation: Confirmation, result: (Result<String>) -> Unit): Boolean {
        val current = account
        if (confirmation.account !== current || submitting.value || requests.isDisposed) return false
        submitting.value = true
        current.client.sendParentOrder(SendParentOrderRequest(confirmation.order))
            .map { response -> requireNotNull(response.parentOrderAcceptanceId?.takeIf(String::isNotBlank)) }
            .subscribeOn(io).observeOn(main)
            .takeUntil(current.ended.toObservable<Unit>())
            .doFinally { submitting.value = false }
            .subscribe({ accepted ->
                refresh()
                result(Result.success(accepted))
            }, {
                refresh()
                result(Result.failure(it))
            })
            .addTo(requests)
        return true
    }

    fun cancellationConfirmation(order: StopOrder): CancellationConfirmation =
        CancellationConfirmation(account, order.acceptanceId)

    fun cancel(confirmation: CancellationConfirmation, failed: (CancellationFailure) -> Unit) {
        val current = account
        val id = confirmation.acceptanceId
        val order = orders.value.firstOrNull { it.acceptanceId == id }
        val rejection = when {
            confirmation.account !== current -> CancellationFailure.ACCOUNT_CHANGED
            requests.isDisposed || order == null -> CancellationFailure.UNAVAILABLE
            id in cancelling.value -> CancellationFailure.IN_PROGRESS
            !order.canCancel -> CancellationFailure.INELIGIBLE
            else -> null
        }
        if (rejection != null) return failed(rejection)

        cancelling.value = cancelling.value + id
        current.client.cancelParentOrder(CancelParentOrderRequest(id))
            .subscribeOn(io).observeOn(main)
            .toObservable<Unit>().takeUntil(current.ended.toObservable<Unit>())
            .doFinally { cancelling.value = cancelling.value - id }
            .subscribe({}, {
                if (current === account) failed(CancellationFailure.REQUEST_FAILED)
            }, { if (current === account) refresh() })
            .addTo(requests)
    }

    fun onEvents(batch: List<ParentOrderEvent>) {
        val updates = batch.filter { it.productCode == "FX_BTC_JPY" && it.eventType != "ORDER" }
        updates.forEach { event ->
            val id = event.parentOrderAcceptanceId
            val previous = events[id]
            if (previous == null || event.eventDate >= previous.eventDate) events[id] = event
        }
        while (events.size > 200) events.remove(events.keys.first())
        if (updates.isNotEmpty()) orders.value = orders.value.map(::applyEvent)
    }

    fun onPrivateChannelAvailability(ready: Boolean) {
        realtimeDisconnected = !ready
        if (ready) refreshIfEnabled(recoverTriggers = true) else invalidateWaitingOrders()
    }

    fun refreshIfEnabled(recoverTriggers: Boolean = false) {
        if (enabled) refresh(recoverTriggers)
    }

    fun refresh(recoverTriggers: Boolean = false) {
        if (requests.isDisposed) return
        enabled = true
        if (recoverTriggers) invalidateWaitingOrders()
        recoverOnRefresh = recoverOnRefresh || recoverTriggers
        val recover = recoverOnRefresh
        val current = account
        val client = current.client
        val previous = orders.value.associateBy { it.acceptanceId }
        val knownEvents = events.mapValues { it.value.eventType }
        val request = Observable.defer {
            Observable.zip(loadActiveParents(client).toList().toObservable(), client.getParentOrders()) { active, recent ->
                val history = recent.filter(::isCryptoCfdStop)
                val byId = history.associateBy { it.parentOrderAcceptanceId }
                val open = active.filter(::isCryptoCfdStop).map { byId[it.parentOrderAcceptanceId] ?: it }
                val pending = (open + history.filter { it.parentOrderState == "ACTIVE" })
                (pending + history.filter { it.parentOrderState != "ACTIVE" }.take(10))
                    .distinctBy { it.parentOrderAcceptanceId }
            }
        }.flatMap { rows ->
            Observable.fromIterable(rows).concatMap { row ->
                val cached = previous[row.parentOrderAcceptanceId]
                val event = knownEvents[row.parentOrderAcceptanceId] ?: cached?.event
                val parent = row.copy(executedSize = maxOf(row.executedSize, cached?.parent?.executedSize ?: 0.0))
                loadStop(client, parent, cached?.triggerPrice).concatMap { stop ->
                    val updated = stop.copy(event = event)
                    when {
                        updated.status == StopOrderStatus.TRIGGERED ||
                            updated.isPartiallyExecuted -> reconcileChildExecution(client, updated)
                        updated.status == StopOrderStatus.WAITING &&
                            (cached?.triggerPrice == null || cached.triggerVerified == false || recover) ->
                            reconcileChildExecution(client, updated)
                        else -> Observable.just(updated)
                    }
                }
            }
                .toList().toObservable()
        }.subscribeOn(io).observeOn(main)
        refreshRequest.set(request.subscribe({ rows ->
            if (current === account) {
                recoverOnRefresh = false
                val updated = rows.map(::applyEvent)
                orders.value = if (realtimeDisconnected) updated.map(::invalidateWaitingOrder) else updated
                loadFailed.value = rows.any { it.triggerPrice == null || !it.triggerVerified }
            }
        }, {
            if (current === account) {
                invalidateWaitingOrders()
                loadFailed.value = true
            }
        }))
    }

    private fun invalidateWaitingOrders() {
        orders.value = orders.value.map(::invalidateWaitingOrder)
    }

    private fun invalidateWaitingOrder(order: StopOrder): StopOrder =
        if (order.status == StopOrderStatus.WAITING) order.copy(triggerVerified = false) else order

    private fun loadActiveParents(client: APIClientV1, before: Long? = null): Observable<ParentOrder> {
        val pageSize = 100
        return client.getParentOrders(orderState = "ACTIVE", count = pageSize, before = before)
            .concatMap { page ->
                val rows = Observable.fromIterable(page)
                if (page.size < pageSize) return@concatMap rows
                val next = page.minOf { it.id }
                check(next > 0 && (before == null || next < before)) {
                    "Active parent order cursor did not advance"
                }
                rows.concatWith(Observable.defer { loadActiveParents(client, next) })
            }
    }

    private fun applyEvent(order: StopOrder): StopOrder =
        order.copy(event = events[order.acceptanceId]?.eventType ?: order.event)

    private fun reconcileChildExecution(client: APIClientV1, stop: StopOrder): Observable<StopOrder> =
        client.getParentChildOrders(stop.parent.parentOrderId)
            .map { children ->
                val marketOrder = children.firstOrNull { it.childOrderType == "MARKET" }
                if (marketOrder == null) stop else stop.copy(
                    parent = stop.parent.copy(executedSize = maxOf(stop.parent.executedSize, marketOrder.executedSize)),
                    event = stop.event ?: "TRIGGER"
                )
            }
            .onErrorReturnItem(
                if (stop.status == StopOrderStatus.WAITING) stop.copy(triggerVerified = false) else stop
            )

    private fun loadStop(client: APIClientV1, order: ParentOrder, cachedPrice: Long?): Observable<StopOrder> {
        if (cachedPrice != null) return Observable.just(StopOrder(order, cachedPrice))
        return client.getParentOrder(order.parentOrderId).flatMap { details ->
            val parameter = details.parameters.singleOrNull()
            if (details.orderMethod == "SIMPLE" && parameter?.conditionType == "STOP" &&
                parameter.productCode == "FX_BTC_JPY") {
                Observable.just(StopOrder(order, parameter.triggerPrice.takeIf { it > 0 }))
            } else {
                Observable.empty()
            }
        }.onErrorReturn { StopOrder(order, null) }
    }

    private fun isCryptoCfdStop(order: ParentOrder) =
        order.productCode == "FX_BTC_JPY" && order.parentOrderType == "STOP"

    enum class CancellationFailure { ACCOUNT_CHANGED, UNAVAILABLE, IN_PROGRESS, INELIGIBLE, REQUEST_FAILED }

    class CancellationConfirmation internal constructor(internal val account: Account, val acceptanceId: String)

    class Confirmation internal constructor(internal val account: Account, val order: StopOrderRequest)

    class Account(val client: APIClientV1) {
        val ended = CompletableSubject.create()
    }
}
