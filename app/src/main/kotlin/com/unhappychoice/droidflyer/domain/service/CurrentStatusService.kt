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
import io.reactivex.subjects.CompletableSubject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<(Board) -> Board>,
    orderEvents: Observable<Unit>,
    privateClient: APIClientV1 = apiClient
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1) : this(
        apiClient,
        realtimeClient.executions,
        boardUpdates(realtimeClient.boardSnapshot, realtimeClient.board),
        Observable.merge(
            realtimeClient.childOrderEvents.map { Unit },
            realtimeClient.parentOrderEvents.map { Unit }
        ),
        privateClient
    )

    internal val privateState = PrivateTradingState(privateClient)

    internal val currentPrice = Variable(MarketPrice(null))
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), privateState.collateral.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val bag = CompositeDisposable()
    private var accountChanged = CompletableSubject.create()
    private val statusRefresh = StatusRefreshCoordinator(::refreshStatus)
    private val realtimeBoardRevision = AtomicLong()

    init {
        executions
            .filter { it.isNotEmpty() }
            .subscribeOnIoObserveOnUI()
            .subscribeNext { batch ->
                batch.firstOrNull { it.price.isFinite() && it.price > 0.0 }?.let {
                    currentPrice.value = MarketPrice(it.price)
                }
            }
            .addTo(bag)

        boardUpdates
            .doOnNext { realtimeBoardRevision.incrementAndGet() }
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

    fun dispose() {
        bag.dispose()
        statusRefresh.dispose()
        privateState.dispose()
    }

    fun updateStatus() = statusRefresh.request(followUp = true)

    fun pollStatus() = statusRefresh.request(followUp = false)

    internal fun switchAccount(client: APIClientV1) {
        if (bag.isDisposed) return

        val previous = accountChanged
        accountChanged = CompletableSubject.create()
        privateState.switchAccount(client)
        // Queue the new account before ending an in-flight refresh.
        updateStatus()
        previous.onComplete()
    }

    private fun refreshStatus(): Completable = Completable.ambArray(
        accountChanged,
        Completable.mergeArray(privateState.refresh(), refreshBoard())
    )

    private fun refreshBoard(): Completable {
        val requestRevision = realtimeBoardRevision.get()
        return apiClient.getBoard("FX_BTC_JPY")
            .subscribeOnIoObserveOnUI()
            .publishStatus { snapshot ->
                if (realtimeBoardRevision.get() == requestRevision) board.value = snapshot
            }
    }
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
