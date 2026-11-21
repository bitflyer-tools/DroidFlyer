package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.submitChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.ReplaySubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1?,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = AndroidSchedulers.mainThread()
) {
    val position = Variable<List<Position>>(emptyList())
    val balance = Variable(0.0)
    val orders = Variable<List<ChildOrder>>(emptyList())

    private var session = client?.let(::Session)
    private var disposed = false

    fun orderRows(): Observable<List<OrderRow>> =
        orders.asObservable().map { currentOrders ->
            val current = session
            current?.let { active -> currentOrders.map { OrderRow(active, it) } }.orEmpty()
        }

    fun dispose() {
        disposed = true
        replaceSession(null)
    }

    fun switchAccount(client: APIClientV1?) {
        if (disposed) return

        replaceSession(client?.let(::Session))
    }

    fun unlink() = replaceSession(null)

    fun refresh(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session ?: return@defer Completable.complete()
        val client = current.client
        Completable.mergeArray(
            client.getPositions().publishResponse { if (!disposed && current === session) position.value = it },
            client.getCollateral().map { it["collateral"] as? Double ?: 0.0 }
                .publishResponse { if (!disposed && current === session) balance.value = it },
            client.getChildOrders("FX_BTC_JPY")
                .publishResponse { if (!disposed && current === session && orders.value != it) orders.value = it }
        ).takeUntil(current.ended.firstElement().ignoreElement())
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

    fun submitOrder(request: SendChildOrderRequest): Observable<ChildOrderAcceptance>? {
        val current = session ?: return null
        return withinSession(current, current.client.submitChildOrder(request))
    }

    fun cancelOrder(row: OrderRow): Observable<Unit>? {
        val current = session ?: return null
        if (row.session !== current) return null
        val order = orders.value.firstOrNull {
            it.productCode == row.order.productCode &&
                it.childOrderAcceptanceId == row.order.childOrderAcceptanceId
        } ?: return null
        val request = CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId)
        return withinSession(current, current.client.cancelChildOrder(request).andThen(Observable.just(Unit)))
    }

    private fun replaceSession(next: Session?) {
        val previous = session
        session = next
        orders.value = emptyList()
        position.value = emptyList()
        balance.value = 0.0
        previous?.ended?.onNext(Unit)
    }

    private fun <T : Any> withinSession(current: Session, source: Observable<T>): Observable<T> =
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .takeUntil(current.ended)

    private fun <T : Any> Observable<T>.publishResponse(publish: (T) -> Unit): Completable =
        subscribeOn(requestScheduler).observeOn(stateScheduler).publishStatus(publish)

    data class OrderRow(internal val session: Session, val order: ChildOrder)
    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        val ended = ReplaySubject.createWithSize<Unit>(1)
    }
}
