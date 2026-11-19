package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
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
    private val requests = CompositeDisposable()

    fun dispose() {
        requests.dispose()
        replaceSession(null)
    }

    fun switchAccount(client: APIClientV1) {
        if (requests.isDisposed) return

        replaceSession(Session(client))
        refresh()
    }

    fun unlink() = replaceSession(null)

    fun refresh() {
        if (requests.isDisposed) return

        val current = session ?: return
        val requestId = ++current.positionRequestId
        current.client.getPositions()
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { positions ->
                if (current === session) {
                    position.value = positions
                    current.positionResponses.onNext(requestId to positions)
                }
            }
            .addTo(requests)
        bind(current, current.client.getCollateral().map { it["collateral"] as? Double ?: 0.0 }, balance)
        // Unchanged rows keep their order references for cancellation.
        current.client.getChildOrders("FX_BTC_JPY")
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { if (current === session && orders.value != it) orders.value = it }
            .addTo(requests)
    }

    fun observeNewPositionResponses(): Observable<List<Position>> {
        val current = session ?: return Observable.empty()
        val latestRequestId = current.positionRequestId
        return current.positionResponses
            .filter { (requestId, _) -> requestId > latestRequestId }
            .map { (_, positions) -> positions }
    }

    fun isCurrent(confirmation: CloseConfirmation): Boolean = confirmation.session === session

    fun closeConfirmation(): CloseConfirmation? {
        val current = session ?: return null
        return PositionCloseOrder.from(position.value.wholeSize())?.let { CloseConfirmation(current, it) }
    }

    fun closePosition(
        confirmation: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit = {}
    ): Observable<Map<String, Any>>? {
        val current = session ?: return null
        if (!isCurrent(confirmation)) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", it.side, null, it.size)
                withinSession(current, current.client.sendChildOrder(request))
            },
            reconfirm = { reconfirm(CloseConfirmation(current, it)) }
        )
    }

    fun submitOrder(request: SendChildOrderRequest): Observable<Map<String, Any>>? {
        val current = session ?: return null
        return withinSession(current, current.client.sendChildOrder(request))
    }

    fun cancelOrder(order: ChildOrder): Observable<Unit>? {
        val current = session ?: return null
        if (orders.value.none { it === order }) return null
        val request = CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId)
        return withinSession(current, current.client.cancelChildOrder(request).andThen(Observable.just(Unit)))
    }

    private fun replaceSession(next: Session?) {
        val previous = session
        session = next
        requests.clear()
        previous?.ended?.onNext(Unit)
        previous?.positionResponses?.onComplete()
        position.value = emptyList()
        balance.value = 0.0
        orders.value = emptyList()
    }

    private fun <T : Any> withinSession(current: Session, source: Observable<T>): Observable<T> =
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .takeUntil(current.ended)

    private fun <T : Any> bind(current: Session, source: Observable<T>, target: Variable<T>) {
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { if (current === session) target.value = it }
            .addTo(requests)
    }

    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        val ended = ReplaySubject.createWithSize<Unit>(1)
        var positionRequestId = 0L
        val positionResponses = PublishSubject.create<Pair<Long, List<Position>>>()
    }
}
