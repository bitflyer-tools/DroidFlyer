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
import io.reactivex.schedulers.Schedulers

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

    fun switchAccount(client: APIClientV1) {
        session = Session(client)
        position.value = emptyList()
        balance.value = 0.0
        orders.value = emptyList()
        refresh()
    }

    fun refresh() {
        val current = session
        bind(current, current.client.getPositions(), position)
        bind(current, current.client.getCollateral().map { it["collateral"] as? Double ?: 0.0 }, balance)
        // Unchanged rows keep their order references for cancellation.
        current.client.getChildOrders("FX_BTC_JPY")
            .subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { if (current === session && orders.value != it) orders.value = it }
    }

    fun closeConfirmation(): CloseConfirmation? {
        return PositionCloseOrder.from(position.value.wholeSize())?.let { CloseConfirmation(session, it) }
    }

    fun closePosition(
        confirmation: CloseConfirmation,
        reconfirm: (CloseConfirmation) -> Unit = {}
    ): Observable<Map<String, Any>>? {
        val current = session
        if (confirmation.session !== current) return null
        return confirmation.order.confirm(
            PositionCloseOrder.from(position.value.wholeSize()),
            submit = {
                val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", it.side, null, it.size)
                current.client.sendChildOrder(request)
            },
            reconfirm = { reconfirm(CloseConfirmation(current, it)) }
        )
    }

    fun cancelOrder(order: ChildOrder): Observable<String>? {
        if (orders.value.none { it === order }) return null
        val request = CancelChildOrderRequest("FX_BTC_JPY", order.childOrderAcceptanceId)
        return session.client.cancelChildOrder(request)
    }

    private fun <T : Any> bind(current: Session, source: Observable<T>, target: Variable<T>) {
        source.subscribeOn(requestScheduler)
            .observeOn(stateScheduler)
            .subscribeNext { if (current === session) target.value = it }
    }

    class CloseConfirmation internal constructor(internal val session: Session, val order: PositionCloseOrder)
    class Session(val client: APIClientV1)
}
