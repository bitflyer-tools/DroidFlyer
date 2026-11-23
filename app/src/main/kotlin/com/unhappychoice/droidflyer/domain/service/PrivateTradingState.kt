package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.submitChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1?,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = AndroidSchedulers.mainThread(),
    submissionGuard: StopSubmissionGuard = StopSubmissionGuard.inMemory(),
    maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM)
) {
    val position = Variable<List<Position>>(emptyList())
    val collateral = Variable(Collateral(null))
    val orders = Variable<List<ChildOrder>>(emptyList())

    val stopOrders = StopOrderService(client, requestScheduler, stateScheduler, submissionGuard, maximumOrderSize)
    val accountChanges: Observable<Unit> get() = accountSwitches.hide()

    private var session = client?.let(::Session)
    private val accountSwitches = PublishSubject.create<Unit>()
    private var disposed = false

    val ordersMayBeIncomplete = orders.asObservable()
        .map { it.size >= ACTIVE_ORDER_LIMIT }
        .distinctUntilChanged()

    fun orderRows(): Observable<List<OrderRow>> =
        orders.asObservable().map { currentOrders ->
            val current = session
            current?.let { active -> currentOrders.map { OrderRow(active, it) } }.orEmpty()
        }

    fun dispose() {
        disposed = true
        stopOrders.dispose()
        replaceSession(null)
    }

    fun switchAccount(
        client: APIClientV1?,
        guard: StopSubmissionGuard = StopSubmissionGuard.inMemory(),
        onSwitched: () -> Unit = {}
    ) {
        if (disposed) return

        replaceSession(client?.let(::Session)) {
            stopOrders.switchAccount(client, guard)
            accountSwitches.onNext(Unit)
            onSwitched()
        }
    }

    fun unlink(onSwitched: () -> Unit = {}) = switchAccount(null, onSwitched = onSwitched)

    fun refresh(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session ?: return@defer Completable.complete()
        val client = current.client
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
        ).takeUntil(current.ended.firstElement().ignoreElement())
    }

    fun getMyExecutions(count: Int, before: Long?): Single<List<PrivateExecution>> {
        val current = session ?: return Single.error(IllegalStateException("API credentials are unavailable"))
        return withinSession(current, Observable.defer { current.client.getMyExecutions(count, before) })
            .singleOrError()
    }

    fun prepareOrder(request: SendChildOrderRequest): NewOrder? =
        session?.let { NewOrder(it, request) }

    fun submitOrder(order: NewOrder): Observable<ChildOrderAcceptance>? {
        val current = order.session
        if (current !== session) return null
        return withinSession(current, Observable.defer { current.client.submitChildOrder(order.request) })
    }

    fun isCurrent(confirmation: CloseConfirmation): Boolean = !disposed && confirmation.session === session

    fun closeConfirmation(): CloseConfirmation? {
        val current = session ?: return null
        return PositionCloseOrder.from(position.value.wholeSize())?.let { CloseConfirmation(current, it) }
    }

    fun closePosition(
        confirmation: CloseConfirmation,
        onAccepted: () -> Unit = {},
        reconfirm: (CloseConfirmation) -> Unit = {}
    ): Observable<Unit>? {
        val current = session ?: return null
        if (!isCurrent(confirmation)) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                withinSession(current, Observable.defer {
                    ClosePositionService(current.client, requestScheduler).close(it.positionSize, onAccepted).toObservable()
                })
            },
            reconfirm = { reconfirm(CloseConfirmation(current, it)) }
        )
    }

    fun cancelOrder(row: OrderRow): Observable<Unit>? {
        val current = session ?: return null
        if (row.session !== current) return null
        val order = orders.value.firstOrNull {
            it.productCode == row.order.productCode &&
                it.childOrderAcceptanceId == row.order.childOrderAcceptanceId
        } ?: return null
        val request = CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId)
        return withinSession(current, Observable.defer {
            current.client.cancelChildOrder(request).andThen(Observable.just(Unit))
        })
    }

    private fun replaceSession(next: Session?, onSwitched: () -> Unit = {}) {
        val previous = session
        session = next
        orders.value = emptyList()
        position.value = emptyList()
        collateral.value = Collateral(null)
        try {
            onSwitched()
        } finally {
            previous?.ended?.onNext(Unit)
        }
    }

    private fun <T : Any> withinSession(current: Session, source: Observable<T>): Observable<T> =
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .takeUntil(current.ended)

    private fun <T : Any> Observable<T>.publishResponse(publish: (T) -> Unit): Completable =
        subscribeOn(requestScheduler).observeOn(stateScheduler).publishStatus(publish)

    class NewOrder internal constructor(internal val session: Session, val request: SendChildOrderRequest) {
        val sessionEnded: Completable get() = session.ended.firstElement().ignoreElement()
    }

    companion object {
        private const val ACTIVE_ORDER_LIMIT = 500
    }

    data class OrderRow(internal val session: Session, val order: ChildOrder)
    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        val ended = ReplaySubject.createWithSize<Unit>(1)
    }
}
