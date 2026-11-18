package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.bindTo
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<Board>,
    orderEvents: Observable<Unit>
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient) : this(
        apiClient,
        realtimeClient.executions,
        realtimeClient.board,
        Observable.merge(
            realtimeClient.childOrderEvents.map { Unit },
            realtimeClient.parentOrderEvents.map { Unit }
        )
    )

    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = Variable<List<Position>>(listOf())
    val balance = Variable(0.0)
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = Variable<List<ChildOrder>>(listOf())

    private val positionRequestId = AtomicLong()
    private val positionResponses = PublishSubject.create<Pair<Long, List<Position>>>()
    private val bag = CompositeDisposable()

    init {
        executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        boardUpdates
            .subscribeOnIoObserveOnUI()
            .subscribeNext { board.value = board.value.merge(it) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext {
                buyPrice.value = board.value.asks.map { it.price }.minOrNull() ?: 0
                sellPrice.value = board.value.bids.map { it.price }.maxOrNull() ?: 0
            }.addTo(bag)

        orderEvents
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun profit(): Long = position.value.profit(currentPrice.value.toLong()).toLong()

    fun spread(): Long = currentPrice.value.toLong() - position.value.average().toLong()

    fun observeNewPositionResponses(): Observable<List<Position>> {
        val latestRequestId = positionRequestId.get()
        return positionResponses
            .filter { (requestId, _) -> requestId > latestRequestId }
            .map { (_, positions) -> positions }
    }

    fun updateStatus() {
        val requestId = positionRequestId.incrementAndGet()
        apiClient.getPositions()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { positions ->
                position.value = positions
                positionResponses.onNext(requestId to positions)
            }
            .addTo(bag)

        apiClient.getCollateral()
            .subscribeOnIoObserveOnUI()
            .map { it["collateral"] as? Double ?: 0.0 }
            .bindTo(balance)
            .addTo(bag)

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(board)
            .addTo(bag)

        apiClient.getChildOrders("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .bindTo(orders)
            .addTo(bag)
    }
}
