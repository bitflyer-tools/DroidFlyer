package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<(Board) -> Board>,
    orderEvents: Observable<Unit>
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
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
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), balance.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = Variable<List<ChildOrder>>(listOf())

    private val positionRequestId = AtomicLong()
    private val positionResponses = PublishSubject.create<Pair<Long, List<Position>>>()
    private val bag = CompositeDisposable()
    private val statusRefresh = StatusRefreshCoordinator(::refreshStatus)

    init {
        executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        boardUpdates
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { update -> board.value = update(board.value) }
            .addTo(bag)

        board.asObservable()
            .subscribeOnIoObserveOnUI()
            .subscribeNext { it.updateMarketQuotes(buyPrice, sellPrice) }
            .addTo(bag)

        orderEvents
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun observeNewPositionResponses(): Observable<List<Position>> {
        val latestRequestId = positionRequestId.get()
        return positionResponses
            .filter { (requestId, _) -> requestId > latestRequestId }
            .map { (_, positions) -> positions }
    }

    fun updateStatus() = statusRefresh.request(followUp = true)

    fun pollStatus() = statusRefresh.request(followUp = false)

    private fun refreshStatus(): Completable {
        val requestId = positionRequestId.incrementAndGet()
        return Completable.mergeArray(
            apiClient.getPositions().publishStatus { positions ->
                position.value = positions
                positionResponses.onNext(requestId to positions)
            },
            apiClient.getCollateral().map { it["collateral"] as? Double ?: 0.0 }.publishTo(balance),
            apiClient.getBoard("FX_BTC_JPY").publishTo(board),
            apiClient.getChildOrders("FX_BTC_JPY").publishTo(orders)
        )
    }

    private fun <T : Any> Observable<T>.publishTo(variable: Variable<T>): Completable =
        publishStatus { variable.value = it }

    private fun <T : Any> Observable<T>.publishStatus(publish: (T) -> Unit): Completable =
        subscribeOnIoObserveOnUI()
            .doOnNext { publish(it) }
            .ignoreElements()
            .doOnError { it.printStackTrace() }
            .onErrorComplete()
}

internal fun Board.updateMarketQuotes(buyPrice: Variable<Long>, sellPrice: Variable<Long>) {
    val bestAsk = asks.minOfOrNull(Order::price) ?: 0L
    val bestBid = bids.maxOfOrNull(Order::price) ?: 0L
    if (buyPrice.value != bestAsk) buyPrice.value = bestAsk
    if (sellPrice.value != bestBid) sellPrice.value = bestBid
}

internal fun boardUpdates(
    snapshots: Observable<Board>,
    deltas: Observable<Board>
): Observable<(Board) -> Board> = Observable.merge(
    snapshots.map<(Board) -> Board> { snapshot -> { snapshot } },
    deltas.map<(Board) -> Board> { delta -> { book -> book.merge(delta) } }
)
