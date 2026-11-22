package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.submitChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1?,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = androidMainThreadScheduler()
) {
    val position = Variable<List<Position>>(emptyList())
    val collateral = Variable(Collateral(null))
    val orders = Variable<List<ChildOrder>>(emptyList())

    private var session = Session(client)
    private var disposed = false

    val ordersMayBeIncomplete = orders.asObservable()
        .map { it.size >= ACTIVE_ORDER_LIMIT }
        .distinctUntilChanged()

    fun orderRows(): Observable<List<OrderRow>> =
        orders.asObservable().map { currentOrders ->
            val current = session
            currentOrders.map { OrderRow(current, it) }
        }

    fun dispose() {
        disposed = true
        session.ended.onComplete()
    }

    fun switchAccount(client: APIClientV1?, onSwitched: () -> Unit = {}) {
        if (disposed) return

        val previous = session
        session = Session(client)
        orders.value = emptyList()
        position.value = emptyList()
        collateral.value = Collateral(null)
        try {
            onSwitched()
        } finally {
            previous.ended.onComplete()
        }
    }

    fun refresh(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session
        val client = current.client ?: return@defer Completable.complete()
        Completable.mergeArray(
            client.getPositions().publishResponse { if (!disposed && current === session) position.value = it },
            client.getCollateral().flatMapMaybe { response ->
                val amount = response["collateral"] as? Number
                amount?.let { Maybe.just(Collateral(it.toDouble())) } ?: Maybe.empty()
            }
                .publishResponse { if (!disposed && current === session) collateral.value = it },
            // Unchanged rows keep their order references for cancellation.
            client.getChildOrders("FX_BTC_JPY", count = ACTIVE_ORDER_LIMIT)
                .publishResponse { if (!disposed && current === session && orders.value != it) orders.value = it }
        ).takeUntil(current.ended)
    }

    fun prepareOrder(request: SendChildOrderRequest): NewOrder? =
        if (disposed || session.client == null) null else NewOrder(session, request)

    fun submitOrder(order: NewOrder): Observable<ChildOrderAcceptance>? {
        val current = order.session
        val client = current.client ?: return null
        if (disposed || current !== session) return null

        return Observable.defer {
            if (current.ended.hasComplete()) Observable.empty()
            else client.submitChildOrder(order.request)
        }
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .takeUntil(order.sessionEnded.toObservable<Unit>())
    }

    fun isCurrent(confirmation: CloseConfirmation): Boolean = !disposed && confirmation.session === session

    fun closeConfirmation(): CloseConfirmation? {
        return PositionCloseOrder.from(position.value.wholeSize())?.let { CloseConfirmation(session, it) }
    }

    fun closePosition(
        confirmation: CloseConfirmation,
        onAccepted: () -> Unit = {},
        reconfirm: (CloseConfirmation) -> Unit = {}
    ): Observable<Unit>? {
        val current = session
        val client = current.client ?: return null
        if (!isCurrent(confirmation)) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                Observable.defer {
                    ClosePositionService(client, requestScheduler).close(it.positionSize, onAccepted).toObservable()
                }
                    .subscribeOn(requestScheduler)
                    .observeOn(stateScheduler)
                    .takeUntil(current.ended.toObservable<Unit>())
            },
            reconfirm = { reconfirm(CloseConfirmation(current, it)) }
        )
    }

    fun cancelOrder(row: OrderRow): Completable? {
        val current = session
        if (disposed || row.session !== current) return null
        val order = orders.value.firstOrNull {
            it.productCode == row.order.productCode &&
                it.childOrderAcceptanceId == row.order.childOrderAcceptanceId
        } ?: return null
        return current.client?.cancelChildOrder(CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId))
    }

    private fun <T : Any> Observable<T>.publishResponse(publish: (T) -> Unit): Completable =
        subscribeOn(requestScheduler).observeOn(stateScheduler).publishStatus(publish)

    class NewOrder internal constructor(internal val session: Session, val request: SendChildOrderRequest) {
        val sessionEnded: Completable get() = session.ended
    }

    companion object {
        private const val ACTIVE_ORDER_LIMIT = 500
    }

    data class OrderRow(internal val session: Session, val order: ChildOrder)
    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1?) {
        val ended = CompletableSubject.create()
    }
}
