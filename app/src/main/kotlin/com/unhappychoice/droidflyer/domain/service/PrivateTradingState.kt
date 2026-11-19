package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = AndroidSchedulers.mainThread()
) {
    val position = Variable<List<Position>>(emptyList())
    val balance = Variable(0.0)
    val orders = Variable<List<ChildOrder>>(emptyList())

    val accountChanges: Observable<Unit> get() = accountSwitches.hide()

    private var session = Session(client)
    private val accountSwitches = PublishSubject.create<Unit>()
    private val requests = CompositeDisposable()

    fun dispose() {
        requests.dispose()
        session.positionResponses.onComplete()
    }

    fun switchAccount(client: APIClientV1) {
        if (requests.isDisposed) return

        requests.clear()
        val previous = session
        session = Session(client)
        previous.positionResponses.onComplete()
        position.value = emptyList()
        balance.value = 0.0
        orders.value = emptyList()
        accountSwitches.onNext(Unit)
        refresh()
    }

    fun refresh() {
        if (requests.isDisposed) return

        val current = session
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

    fun getMyExecutions(count: Int, before: Long?): Single<List<PrivateExecution>> =
        session.client.getMyExecutions(count, before)
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .singleOrError()

    fun observeNewPositionResponses(): Observable<List<Position>> {
        val current = session
        val latestRequestId = current.positionRequestId
        return current.positionResponses
            .filter { (requestId, _) -> requestId > latestRequestId }
            .map { (_, positions) -> positions }
    }

    fun isCurrent(confirmation: CloseConfirmation): Boolean = confirmation.session === session

    fun closeConfirmation(): CloseConfirmation? {
        return PositionCloseOrder.from(position.value.wholeSize())?.let { CloseConfirmation(session, it) }
    }

    fun closePosition(
        confirmation: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit = {}
    ): Observable<Map<String, Any>>? {
        val current = session
        if (!isCurrent(confirmation)) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", it.side, null, it.size)
                current.client.sendChildOrder(request)
            },
            reconfirm = { reconfirm(CloseConfirmation(current, it)) }
        )
    }

    fun cancelOrder(order: ChildOrder): Completable? {
        if (orders.value.none { it === order }) return null
        val request = CancelChildOrderRequest(order.productCode, order.childOrderAcceptanceId)
        return session.client.cancelChildOrder(request)
    }

    private fun <T : Any> bind(current: Session, source: Observable<T>, target: Variable<T>) {
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { if (current === session) target.value = it }
            .addTo(requests)
    }

    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        var positionRequestId = 0L
        val positionResponses = PublishSubject.create<Pair<Long, List<Position>>>()
    }
}
