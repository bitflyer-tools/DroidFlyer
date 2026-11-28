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
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1?,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = androidMainThreadScheduler(),
    submissionGuard: StopSubmissionGuard = StopSubmissionGuard.inMemory(),
    maximumOrderSize: Variable<Double> = Variable(CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM)
) {
    enum class ActiveOrdersStatus { INITIAL, EMPTY, AVAILABLE, UNAVAILABLE, STALE }
    enum class ReadStatus { INITIAL, AVAILABLE, STALE, UNAVAILABLE }

    val position = Variable<List<Position>>(emptyList())
    val collateral = Variable(Collateral(null))
    val orders = Variable<List<ChildOrder>>(emptyList())

    val positionStatus = Variable(ReadStatus.INITIAL)
    val collateralStatus = Variable(ReadStatus.INITIAL)
    val activeOrdersStatus = Variable(ActiveOrdersStatus.INITIAL)

    val stopOrders = StopOrderService(client, requestScheduler, stateScheduler, submissionGuard, maximumOrderSize)

    val accountChanges: Observable<Unit> get() = accountSwitches.hide()

    val hasPrivateClient: Boolean get() = !disposed && session != null

    fun observePrivateClientAvailability(): Observable<Boolean> = Observable.defer {
        accountChanges.map { hasPrivateClient }
            .startWith(hasPrivateClient)
            .distinctUntilChanged()
    }

    private var session = client?.let(::Session)
    private val accountSwitches = PublishSubject.create<Unit>()
    private var disposed = false

    val ordersMayBeIncomplete = orders.asObservable()
        .map { it.size >= ACTIVE_ORDER_LIMIT }
        .distinctUntilChanged()

    fun orderRows(): Observable<List<OrderRow>> =
        orders.asObservable().switchMap { currentOrders ->
            val current = session ?: return@switchMap Observable.just(emptyList<OrderRow>())
            current.cancellations.asObservable().map { pending ->
                currentOrders.map { OrderRow(current, it, it.cancellationRequest() in pending) }
            }
        }.distinctUntilChanged()

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

    fun refreshPositionsAndCollateral(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session ?: return@defer Completable.complete()
        val client = current.client
        Completable.mergeArray(
            client.getPositions()
                .subscribeOn(requestScheduler)
                .observeOn(stateScheduler)
                .doOnError { if (!disposed && current === session) positionStatus.value = positionStatus.value.toFailed() }
                .publishStatus {
                    if (!disposed && current === session) {
                        position.value = it
                        positionStatus.value = ReadStatus.AVAILABLE
                    }
                },
            client.getCollateral()
                .map { response ->
                    val amount = response["collateral"] as? Number
                        ?: throw IllegalArgumentException("Expected a numeric collateral amount")
                    Collateral(amount.toDouble())
                }
                .subscribeOn(requestScheduler)
                .observeOn(stateScheduler)
                .doOnError { if (!disposed && current === session) collateralStatus.value = collateralStatus.value.toFailed() }
                .publishStatus {
                    if (!disposed && current === session) {
                        collateral.value = it
                        collateralStatus.value = ReadStatus.AVAILABLE
                    }
                }
        ).takeUntil(current.ended.firstElement().ignoreElement())
    }

    fun refreshActiveOrders(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session ?: return@defer Completable.complete()
        current.client.getChildOrders("FX_BTC_JPY", count = ACTIVE_ORDER_LIMIT)
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .doOnError {
                if (!disposed && current === session) {
                    activeOrdersStatus.value =
                        if (orders.value.isEmpty()) ActiveOrdersStatus.UNAVAILABLE else ActiveOrdersStatus.STALE
                }
            }
            .publishStatus {
                if (!disposed && current === session) {
                    // Unchanged rows keep their order references for cancellation.
                    if (orders.value != it) orders.value = it
                    activeOrdersStatus.value =
                        if (it.isEmpty()) ActiveOrdersStatus.EMPTY else ActiveOrdersStatus.AVAILABLE
                }
            }
            .takeUntil(current.ended.firstElement().ignoreElement())
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

    fun isCurrent(row: OrderRow): Boolean = !disposed && row.session === session

    fun cancelOrder(row: OrderRow): Observable<Unit>? {
        val current = row.session
        val request = row.order.cancellationRequest()
        if (!canCancel(row, request)) return null

        return Observable.defer {
            if (!canCancel(row, request)) return@defer Observable.empty<Unit>()

            current.cancellations.value = current.cancellations.value + request
            withinSession(current, Observable.defer {
                current.client.cancelChildOrder(request).andThen(Observable.just(Unit))
            }).doFinally {
                current.cancellations.value = current.cancellations.value - request
            }
        }
    }

    private fun canCancel(row: OrderRow, request: CancelChildOrderRequest): Boolean =
        isCurrent(row) && request !in row.session.cancellations.value && orders.value.any {
            it.productCode == request.productCode && it.childOrderAcceptanceId == request.childOrderAcceptanceId
        }

    private fun ChildOrder.cancellationRequest() = CancelChildOrderRequest(productCode, childOrderAcceptanceId)

    private fun replaceSession(next: Session?, onSwitched: () -> Unit = {}) {
        val previous = session
        session = next
        orders.value = emptyList()
        activeOrdersStatus.value = ActiveOrdersStatus.INITIAL
        position.value = emptyList()
        collateral.value = Collateral(null)
        positionStatus.value = ReadStatus.INITIAL
        collateralStatus.value = ReadStatus.INITIAL
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

    private fun ReadStatus.toFailed(): ReadStatus =
        if (this == ReadStatus.AVAILABLE || this == ReadStatus.STALE) ReadStatus.STALE else ReadStatus.UNAVAILABLE

    class NewOrder internal constructor(internal val session: Session, val request: SendChildOrderRequest) {
        val sessionEnded: Completable get() = session.ended.firstElement().ignoreElement()
    }

    companion object {
        private const val ACTIVE_ORDER_LIMIT = 500
    }

    data class OrderRow(internal val session: Session, val order: ChildOrder, val isCancelling: Boolean = false)
    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        val ended = ReplaySubject.createWithSize<Unit>(1)
        val cancellations = Variable<Set<CancelChildOrderRequest>>(emptySet())
    }
}
