package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
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

    private var session = Session(client)
    private var disposed = false

    fun dispose() {
        disposed = true
        session.positionResponses.onComplete()
    }

    fun switchAccount(client: APIClientV1) {
        if (disposed) return

        val previous = session
        session = Session(client)
        previous.positionResponses.onComplete()
        position.value = emptyList()
        balance.value = 0.0
        orders.value = emptyList()
    }

    fun refresh(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session
        val requestId = ++current.positionRequestId
        Completable.mergeArray(
            current.client.getPositions().publishResponse { positions ->
                if (current === session) {
                    position.value = positions
                    current.positionResponses.onNext(requestId to positions)
                }
            },
            current.client.getCollateral().map { it["collateral"] as? Double ?: 0.0 }
                .publishResponse { if (current === session) balance.value = it },
            // Unchanged rows keep their order references for cancellation.
            current.client.getChildOrders("FX_BTC_JPY")
                .publishResponse { if (current === session && orders.value != it) orders.value = it }
        )
    }

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

    private fun <T : Any> Observable<T>.publishResponse(publish: (T) -> Unit): Completable =
        subscribeOn(requestScheduler).observeOn(stateScheduler).publishStatus(publish)

    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1) {
        var positionRequestId = 0L
        val positionResponses = PublishSubject.create<Pair<Long, List<Position>>>()
    }
}
