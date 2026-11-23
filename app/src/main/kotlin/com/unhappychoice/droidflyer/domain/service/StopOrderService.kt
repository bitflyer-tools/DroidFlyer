package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ParentOrderEvent
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.SerialDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.CompletableSubject
import retrofit2.HttpException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class StopOrderService(
    client: APIClientV1?,
    private val io: Scheduler,
    private val main: Scheduler,
    guard: StopSubmissionGuard = StopSubmissionGuard.inMemory()
) {
    class SubmissionRejected(cause: HttpException) : RuntimeException("bitFlyer rejected the stop order", cause)

    val orders = Variable<List<StopOrder>>(emptyList())
    val loadFailed = Variable(false)
    val submitting = Variable(false)
    val reconcilingSubmission = Variable(guard.pending() != null)
    val cancelling = Variable<Set<String>>(emptySet())

    val orderEventsReady: Boolean get() = !privateChannelsUnavailable

    private var account = Account(client, guard)
    private val requests = CompositeDisposable()
    private var enabled = false
    private var refreshing = false
    private var realtimeRefresh: RealtimeRefresh? = null
    private var pendingRealtimeRefresh: RealtimeRefresh? = null
    private var recoverOnRefresh = false
    private var privateChannelsUnavailable = true
    private var channelGapOpen = false
    private var missedOrderEvents = false
    private val refreshRequest = SerialDisposable().apply { addTo(requests) }
    private val fallbackRefresh = SerialDisposable().apply { addTo(requests) }
    private val refreshReads = StopOrderFallbackReads(io)
    private val waitingOrUnknown = setOf(StopOrderStatus.WAITING, StopOrderStatus.UNKNOWN)
    private val events = LinkedHashMap<String, ParentOrderEvent>()
    private val childTerminations = LinkedHashMap<String, ChildOrderEvent>()

    fun switchAccount(client: APIClientV1?, guard: StopSubmissionGuard = StopSubmissionGuard.inMemory()) {
        val previous = account
        account = Account(client, guard)
        previous.ended.onComplete()
        reconcilingSubmission.value = guard.pending() != null
        refreshRequest.set(null)
        fallbackRefresh.set(null)
        refreshing = false
        realtimeRefresh = null
        pendingRealtimeRefresh = null
        privateChannelsUnavailable = true
        channelGapOpen = false
        missedOrderEvents = false
        events.clear()
        childTerminations.clear()
        orders.value = emptyList()
        loadFailed.value = false
        if (enabled) refresh(recoverTriggers = true)
    }

    fun dispose() {
        account.ended.onComplete()
        requests.dispose()
    }

    fun confirmation(order: StopOrderRequest): Confirmation = Confirmation(account, order)

    fun confirmSubmissionRecovery(): (() -> Boolean)? {
        val current = account
        val pending = current.guard.pending() ?: return null
        return {
            val recovered = current === account && pending !in activeSubmissions && !requests.isDisposed &&
                current.guard.release(pending)
            if (recovered) reconcilingSubmission.value = false
            recovered
        }
    }

    fun submit(confirmation: Confirmation, result: (Result<String>) -> Unit): Boolean {
        val current = account
        if (confirmation.account !== current || !orderEventsReady || submitting.value ||
            reconcilingSubmission.value || requests.isDisposed
        ) return false
        val client = current.client ?: return false
        val id = UUID.randomUUID().toString()
        activeSubmissions.add(id)
        if (!current.guard.reserve(id)) {
            activeSubmissions.remove(id)
            reconcilingSubmission.value = current.guard.pending() != null
            return false
        }
        submitting.value = true
        Observable.defer { client.sendParentOrder(SendParentOrderRequest(confirmation.order)) }
            .map { response -> requireNotNull(response.parentOrderAcceptanceId?.takeIf(String::isNotBlank)) }
            .map { Result.success(it) }
            .onErrorReturn { error ->
                if (error is HttpException && error.code() in 400..499 && error.code() != 408) {
                    Result.failure(SubmissionRejected(error))
                } else {
                    Result.failure(error)
                }
            }
            .subscribeOn(io).observeOn(main)
            .takeUntil(current.ended.toObservable<Unit>())
            .doFinally {
                activeSubmissions.remove(id)
                submitting.value = false
            }
            .subscribeNext { outcome ->
                if (outcome.isSuccess || outcome.exceptionOrNull() is SubmissionRejected) current.guard.release(id)
                reconcilingSubmission.value = current.guard.pending() != null
                result(outcome)
                refresh()
            }
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
            requests.isDisposed || current.client == null || order == null -> CancellationFailure.UNAVAILABLE
            id in cancelling.value -> CancellationFailure.IN_PROGRESS
            !order.canCancel -> CancellationFailure.INELIGIBLE
            else -> null
        }
        if (rejection != null) return failed(rejection)

        val client = requireNotNull(current.client)
        cancelling.value = cancelling.value + id
        val cancellation = Completable.defer {
            client.cancelParentOrder(CancelParentOrderRequest(id))
        }.subscribeOn(io)
        verifyCancellation(client, requireNotNull(order))
            .andThen(cancellation)
            .observeOn(main)
            .toObservable<Unit>().takeUntil(current.ended.toObservable<Unit>())
            .doFinally { cancelling.value = cancelling.value - id }
            .subscribe({}, {
                if (current === account) {
                    refresh(recoverTriggers = true)
                    val failure = if (it is IneligibleCancellation) {
                        CancellationFailure.INELIGIBLE
                    } else CancellationFailure.REQUEST_FAILED
                    failed(failure)
                }
            }, { if (current === account) refresh() })
            .addTo(requests)
    }

    fun onEvents(batch: List<ParentOrderEvent>) {
        if (account.client == null) return

        val updates = batch.filter { it.productCode == "FX_BTC_JPY" && (it.eventType != "ORDER" || !privateChannelsUnavailable) }
        updates.forEach { event ->
            val id = event.parentOrderAcceptanceId
            val previous = events[id]
            if (previous == null || (event.eventType != "ORDER" && event.eventDate >= previous.eventDate)) {
                events[id] = event
            }
        }
        while (events.size > 200) events.remove(events.keys.first())
        if (updates.isNotEmpty()) orders.value = orders.value.map(::applyEvent)
    }

    fun onChildEvents(batch: List<ChildOrderEvent>) {
        if (account.client == null) return

        val updates = batch.filter {
            it.productCode == "FX_BTC_JPY" && it.affectsStopOrderStatus() &&
                it.terminationStatus() != null && !it.childOrderAcceptanceId.isNullOrBlank()
        }
        updates.forEach { event ->
            val id = event.childOrderAcceptanceId
            val previous = childTerminations[id]
            if (previous == null || event.eventDate >= previous.eventDate) childTerminations[id] = event
        }
        while (childTerminations.size > 200) childTerminations.remove(childTerminations.keys.first())
        if (updates.isNotEmpty()) orders.value = orders.value.map(::applyEvent)
    }

    fun onPrivateChannelAvailability(ready: Boolean) {
        if (!ready && channelGapOpen) return
        if (!ready && orderEventsReady) missedOrderEvents = true
        if ((!ready && !privateChannelsUnavailable) || (ready && channelGapOpen)) {
            events.values.removeAll { it.eventType == "ORDER" }
        }
        privateChannelsUnavailable = !ready
        channelGapOpen = !ready
        if (enabled) refresh(recoverTriggers = true)
    }

    fun onParentUpdates() = queueRealtimeRefresh(RealtimeRefresh.PARENT)

    fun onChildUpdates() = queueRealtimeRefresh(RealtimeRefresh.CHILD)

    fun refresh(recoverTriggers: Boolean = false) = refresh(recoverTriggers, realtime = null)

    private fun queueRealtimeRefresh(update: RealtimeRefresh) {
        if (!enabled || privateChannelsUnavailable || requests.isDisposed) return
        enqueueRealtimeRefresh(update)
        refreshPendingRealtimeUpdates()
    }

    private fun enqueueRealtimeRefresh(update: RealtimeRefresh) {
        pendingRealtimeRefresh = maxOf(pendingRealtimeRefresh ?: update, update)
    }

    private fun refreshPendingRealtimeUpdates() {
        if (refreshing || privateChannelsUnavailable || requests.isDisposed) return
        val update = pendingRealtimeRefresh ?: return
        pendingRealtimeRefresh = null
        refresh(recoverTriggers = false, realtime = update)
    }

    private fun refresh(recoverTriggers: Boolean, realtime: RealtimeRefresh?) {
        if (requests.isDisposed) return
        enabled = true
        val current = account
        val client = current.client ?: return
        refreshing = true
        if (privateChannelsUnavailable || recoverTriggers) {
            pendingRealtimeRefresh = null
        } else {
            realtimeRefresh?.let(::enqueueRealtimeRefresh)
        }
        realtimeRefresh = realtime
        fallbackRefresh.set(null)
        if (recoverTriggers) invalidateWaitingOrders()
        recoverOnRefresh = recoverOnRefresh || recoverTriggers
        val recover = recoverOnRefresh || privateChannelsUnavailable || realtime == RealtimeRefresh.CHILD
        val previous = orders.value.associateBy { it.acceptanceId }
        val knownEvents = events.mapValues { it.value.eventType }
        val canConfirmWaitingFromRest = canUseRestWaitingEvidence
        refreshRequest.set(null)
        var historyFailed = false
        val parents = loadActiveParents(client, reads = refreshReads).filter(::isCryptoCfdStop).toList()
            .flatMapObservable { active ->
                val activeIds = active.map { it.parentOrderAcceptanceId }.toSet()
                val missing = previous.values.filter {
                    it.parent.parentOrderState == "ACTIVE" && it.acceptanceId !in activeIds &&
                        (recover || it.status != StopOrderStatus.WAITING)
                }
                Observable.fromIterable(active.map { it to it.isFullyOutstanding() } +
                    missing.map { it.parent to false })
            }
        val history = loadRecentParents(client) { historyFailed = true }.map { it to false }
        val refreshed = LinkedHashMap<String, StopOrder>()
        val request = Observable.just(parents, history).concatMap { source ->
            source.concatMap { (row, activeAndOutstanding) ->
                val cached = refreshed[row.parentOrderAcceptanceId] ?: previous[row.parentOrderAcceptanceId]
                val realtimeEvent = knownEvents[row.parentOrderAcceptanceId]
                val event = realtimeEvent?.takeUnless { it == "ORDER" } ?: cached?.event
                val parent = row.copy(executedSize = maxOf(row.executedSize, cached?.parent?.executedSize ?: 0.0))
                loadStop(client, parent, cached?.triggerPrice, refreshReads).concatMap { stop ->
                    val restWaitingConfirmed =
                        canConfirmWaitingFromRest && activeAndOutstanding && parent.isFullyOutstanding()
                    val updated = stop.copy(
                        event = event,
                        childTermination = cached?.childTermination,
                        waitingConfirmed = realtimeEvent == "ORDER" || restWaitingConfirmed,
                        restWaitingConfirmed = restWaitingConfirmed
                    )
                    when {
                        updated.status == StopOrderStatus.TRIGGERED ||
                            updated.isPartiallyExecuted ||
                            (realtime == RealtimeRefresh.CHILD && updated.childTermination != null) ->
                            reconcileMarketChild(client, updated, refreshReads)
                        parent.parentOrderState == "ACTIVE" &&
                            updated.status in waitingOrUnknown &&
                            (cached?.triggerPrice == null || !cached.triggerVerified || !cached.waitingConfirmed ||
                                recover || !updated.waitingConfirmed) ->
                            reconcileMarketChild(client, updated, refreshReads)
                        else -> Observable.just(updated)
                    }
                }
            }
        }.doOnNext { refreshed[it.acceptanceId] = it }
            .subscribeOn(io).observeOn(main)
            .doOnNext { row -> if (current === account) publishPacedRow(row) }
            .toList()
        request.doOnSubscribe { refreshRequest.set(it) }.subscribe({ rows ->
            if (current === account) {
                val latest = rows.associateBy { it.acceptanceId }.values
                val incomplete = latest.any { it.triggerPrice == null || !it.triggerVerified }
                recoverOnRefresh = incomplete
                orders.value = latest.map(::applyEvent)
                scheduleFallbackRefresh(incomplete || historyFailed)
                loadFailed.value = incomplete || historyFailed
                finishRefresh()
            }
        }, {
            if (current === account) {
                recoverOnRefresh = true
                scheduleFallbackRefresh(retry = true)
                invalidateWaitingOrders()
                loadFailed.value = true
                finishRefresh()
            }
        })
    }

    private fun finishRefresh() {
        refreshing = false
        realtimeRefresh = null
        refreshPendingRealtimeUpdates()
    }

    private fun scheduleFallbackRefresh(retry: Boolean) {
        if (privateChannelsUnavailable || retry) {
            fallbackRefresh.set(
                Observable.timer(30, TimeUnit.SECONDS, io)
                    .observeOn(main)
                    .subscribeNext {
                        if (privateChannelsUnavailable) refresh() else onChildUpdates()
                    }
            )
        }
    }

    private fun publishPacedRow(row: StopOrder) {
        val updated = applyEvent(row)
        val existing = orders.value
        orders.value = if (existing.any { it.acceptanceId == row.acceptanceId }) {
            existing.map { if (it.acceptanceId == row.acceptanceId) updated else it }
        } else {
            existing + updated
        }
    }

    private fun invalidateWaitingOrders() {
        orders.value = orders.value.map(::invalidateWaitingOrder)
    }

    private fun invalidateWaitingOrder(order: StopOrder): StopOrder =
        order.copy(
            triggerVerified = order.triggerVerified && order.status != StopOrderStatus.WAITING,
            restWaitingConfirmed = false
        )

    private fun loadActiveParents(
        client: APIClientV1,
        before: Long? = null,
        reads: StopOrderFallbackReads? = null
    ): Observable<ParentOrder> {
        val pageSize = 100
        return stopOrderRead(reads) { client.getParentOrders(orderState = "ACTIVE", count = pageSize, before = before) }
            .concatMap { page ->
                val rows = Observable.fromIterable(page)
                if (page.size < pageSize) return@concatMap rows
                val next = page.minOf { it.id }
                check(next > 0 && (before == null || next < before)) {
                    "Active parent order cursor did not advance"
                }
                rows.concatWith(Observable.defer { loadActiveParents(client, next, reads) })
            }
    }

    private fun loadRecentParents(client: APIClientV1, onFailure: () -> Unit): Observable<ParentOrder> =
        Observable.fromIterable(listOf("COMPLETED", "CANCELED", "EXPIRED", "REJECTED"))
            .concatMap { state ->
                refreshReads.read { client.getParentOrders(orderState = state) }
                    .onErrorReturn {
                        onFailure()
                        emptyList()
                    }
            }
            .flatMapIterable { it }
            .filter(::isCryptoCfdStop)
            .toList()
            .flatMapObservable { rows -> Observable.fromIterable(rows.sortedByDescending { it.id }.take(10)) }

    // The child list can omit unfilled terminal orders whose events were missed.
    private val canUseRestWaitingEvidence get() = orderEventsReady && !missedOrderEvents

    private fun applyEvent(order: StopOrder): StopOrder {
        val event = events[order.acceptanceId]
        val childTermination = event?.childOrderAcceptanceId?.let(childTerminations::get)?.terminationStatus()
        return order.copy(
            event = event?.eventType?.takeUnless { it == "ORDER" } ?: order.event,
            waitingConfirmed = event?.eventType == "ORDER" || (canUseRestWaitingEvidence && order.restWaitingConfirmed),
            childTermination = childTermination ?: order.childTermination
        )
    }

    private fun ParentOrder.isFullyOutstanding(): Boolean =
        parentOrderState == "ACTIVE" && size > 0 && outstandingSize == size &&
            executedSize == 0.0 && cancelSize == 0.0

    private fun ChildOrderEvent.terminationStatus(): StopOrderStatus? = when (eventType) {
        "CANCEL" -> StopOrderStatus.CANCELED
        "EXPIRE" -> StopOrderStatus.EXPIRED
        "ORDER_FAILED" -> StopOrderStatus.REJECTED
        else -> null
    }

    private fun verifyCancellation(client: APIClientV1, order: StopOrder): Completable =
        loadActiveParents(client)
            .filter { it.parentOrderAcceptanceId == order.acceptanceId && isCryptoCfdStop(it) }
            .take(1)
            .concatMap { parent ->
                reconcileMarketChild(client, order.copy(parent = parent, restWaitingConfirmed = parent.isFullyOutstanding()))
            }
            .subscribeOn(io).observeOn(main)
            .map { verified ->
                applyEvent(verified).canCancel && orders.value.any {
                    it.acceptanceId == order.acceptanceId && it.canCancel
                }
            }
            .first(false)
            .flatMapCompletable { eligible ->
                if (eligible) Completable.complete() else Completable.error(IneligibleCancellation())
            }

    private enum class RealtimeRefresh { PARENT, CHILD }

    private class IneligibleCancellation : RuntimeException()

    private fun reconcileMarketChild(
        client: APIClientV1,
        stop: StopOrder,
        reads: StopOrderFallbackReads? = null
    ): Observable<StopOrder> =
        stopOrderRead(reads) { client.getParentChildOrders(stop.parent.parentOrderId) }
            .map { children ->
                val marketOrder = children.firstOrNull { it.childOrderType == "MARKET" }
                if (marketOrder == null) stop else stop.copy(
                    parent = stop.parent.copy(executedSize = maxOf(stop.parent.executedSize, marketOrder.executedSize)),
                    event = stop.event ?: "TRIGGER",
                    childTermination = when (marketOrder.childOrderState) {
                        "CANCELED" -> StopOrderStatus.CANCELED
                        "EXPIRED" -> StopOrderStatus.EXPIRED
                        "REJECTED" -> StopOrderStatus.REJECTED
                        else -> stop.childTermination
                    }
                )
            }
            .onErrorReturnItem(stop.copy(triggerVerified = false))

    private fun loadStop(
        client: APIClientV1,
        order: ParentOrder,
        cachedPrice: Long?,
        reads: StopOrderFallbackReads
    ): Observable<StopOrder> {
        if (cachedPrice != null) return Observable.just(StopOrder(order, cachedPrice))
        return reads.read { client.getParentOrder(order.parentOrderId) }.flatMap { details ->
            val parameter = details.parameters.singleOrNull()
            if (details.orderMethod == "SIMPLE" && parameter?.conditionType == "STOP" &&
                parameter.productCode == "FX_BTC_JPY") {
                Observable.just(StopOrder(order, parameter.triggerPrice.takeIf { it > 0 }))
            } else {
                Observable.empty()
            }
        }.onErrorReturn { StopOrder(order, null) }
    }

    private fun <T : Any> stopOrderRead(
        reads: StopOrderFallbackReads?,
        request: () -> Observable<T>
    ): Observable<T> = reads?.read(request) ?: Observable.defer { request() }

    private fun isCryptoCfdStop(order: ParentOrder) =
        order.productCode == "FX_BTC_JPY" && order.parentOrderType == "STOP"

    enum class CancellationFailure { ACCOUNT_CHANGED, UNAVAILABLE, IN_PROGRESS, INELIGIBLE, REQUEST_FAILED }

    class CancellationConfirmation internal constructor(internal val account: Account, val acceptanceId: String)

    class Confirmation internal constructor(internal val account: Account, val order: StopOrderRequest)

    class Account(val client: APIClientV1?, val guard: StopSubmissionGuard) {
        val ended = CompletableSubject.create()
    }

    private companion object {
        val activeSubmissions = ConcurrentHashMap.newKeySet<String>()
    }
}
