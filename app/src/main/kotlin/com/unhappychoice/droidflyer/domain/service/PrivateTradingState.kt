package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.CompletableSubject

// Access the session and its cached data on the UI thread.
internal class PrivateTradingState(
    client: APIClientV1,
    private val requestScheduler: Scheduler = Schedulers.io(),
    private val stateScheduler: Scheduler = AndroidSchedulers.mainThread()
) {
    val position = Variable<List<Position>>(emptyList())
    val collateral = Variable(Collateral(null))
    val orders = Variable<List<ChildOrder>>(emptyList())

    private var session = Session(client)
    private var disposed = false

    fun dispose() {
        disposed = true
        session.ended.onComplete()
    }

    fun switchAccount(client: APIClientV1) {
        if (disposed) return

        val previous = session
        session = Session(client)
        previous.ended.onComplete()
        position.value = emptyList()
        collateral.value = Collateral(null)
        orders.value = emptyList()
    }

    fun refresh(): Completable = Completable.defer {
        if (disposed) return@defer Completable.complete()

        val current = session
        Completable.mergeArray(
            current.client.getPositions().publishResponse { if (!disposed && current === session) position.value = it },
            current.client.getCollateral().flatMapMaybe { response ->
                val amount = response["collateral"] as? Number
                amount?.let { Maybe.just(Collateral(it.toDouble())) } ?: Maybe.empty()
            }
                .publishResponse { if (!disposed && current === session) collateral.value = it },
            // Unchanged rows keep their order references for cancellation.
            current.client.getChildOrders("FX_BTC_JPY")
                .publishResponse { if (!disposed && current === session && orders.value != it) orders.value = it }
        )
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
        if (!isCurrent(confirmation)) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                Observable.defer {
                    ClosePositionService(current.client, requestScheduler).close(it.positionSize, onAccepted).toObservable()
                }
                    .subscribeOn(requestScheduler)
                    .observeOn(stateScheduler)
                    .takeUntil(current.ended.toObservable<Unit>())
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
        val ended = CompletableSubject.create()
    }
}
