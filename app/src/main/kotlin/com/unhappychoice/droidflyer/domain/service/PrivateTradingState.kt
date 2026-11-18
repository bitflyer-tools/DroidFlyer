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
        bind(current, current.client.getChildOrders("FX_BTC_JPY"), orders)
    }

    fun closeConfirmation(): CloseConfirmation? {
        val size = position.value.wholeSize()
        return if (size == 0.0) null else CloseConfirmation(session, size)
    }

    fun closePosition(confirmation: CloseConfirmation): Observable<Map<String, Any>>? {
        if (confirmation.session !== session || confirmation.size != position.value.wholeSize()) return null
        val side = if (confirmation.size > 0.0) "SELL" else "BUY"
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, Math.abs(confirmation.size))
        return session.client.sendChildOrder(request)
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

    class CloseConfirmation internal constructor(internal val session: Session, val size: Double)
    class Session(val client: APIClientV1)
}
