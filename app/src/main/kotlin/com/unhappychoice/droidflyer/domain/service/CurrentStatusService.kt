package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit

class CurrentStatusService internal constructor(
    val apiClient: APIClientV1,
    executions: Observable<List<Execution>>,
    boardUpdates: Observable<BoardUpdate>,
    orderEvents: Observable<Unit>,
    privateClient: APIClientV1 = apiClient
) {
    constructor(apiClient: APIClientV1, realtimeClient: RealtimeClient, privateClient: APIClientV1) : this(
        apiClient,
        realtimeClient.executions,
        Observable.merge(
            realtimeClient.boardSnapshot.map { BoardUpdate(it, BoardSource.SNAPSHOT) },
            realtimeClient.board.map { BoardUpdate(it, BoardSource.DELTA) }
        ),
        Observable.merge(
            realtimeClient.childOrderEvents.map { Unit },
            realtimeClient.parentOrderEvents.map { Unit }
        ),
        privateClient
    )

    internal val privateState = PrivateTradingState(privateClient)

    val currentPrice = Variable(0.0)
    val buyPrice = Variable(0L)
    val sellPrice = Variable(0L)
    val position = privateState.position
    val balance = privateState.balance
    val tradingTotals = observeTradingTotals(
        currentPrice.asObservable(), position.asObservable(), balance.asObservable()
    )
    val board = Variable(Board(0.0, listOf(), listOf()))
    val orders = privateState.orders

    private val restBoards = PublishSubject.create<BoardUpdate>().toSerialized()
    private val bag = CompositeDisposable()

    init {
        executions
            .subscribeOnIoObserveOnUI()
            .subscribeNext { currentPrice.value = it.first().price }
            .addTo(bag)

        observeOrderBook(Observable.merge(boardUpdates, restBoards), Schedulers.single())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeNext { event ->
                when (event) {
                    is OrderBookEvent.View -> board.value = event.board
                    is OrderBookEvent.Quotes -> {
                        if (buyPrice.value != event.bestAsk) buyPrice.value = event.bestAsk
                        if (sellPrice.value != event.bestBid) sellPrice.value = event.bestBid
                    }
                }
            }
            .addTo(bag)

        orderEvents
            .debounce(1, TimeUnit.SECONDS)
            .subscribeOnIoObserveOnUI()
            .subscribeNext { updateStatus() }
            .addTo(bag)

        updateStatus()
    }

    fun updateStatus() {
        privateState.refresh()

        apiClient.getBoard("FX_BTC_JPY")
            .subscribeOn(Schedulers.io())
            .subscribeNext { restBoards.onNext(BoardUpdate(it, BoardSource.REST)) }
            .addTo(bag)
    }
}
