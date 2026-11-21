package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

internal data class LimitOrderRows(val asks: List<Order>, val bids: List<Order>, val groupingSize: Long)

internal fun observeLimitOrderRows(
    selected: Observable<Boolean>,
    boards: Observable<Board>,
    groupingSizes: Observable<Long>,
    orders: Observable<List<ChildOrder>>,
    renderScheduler: Scheduler,
    preparationScheduler: Scheduler = Schedulers.computation()
): Observable<Pair<LimitOrderRows, List<ChildOrder>>> = selected.distinctUntilChanged().switchMap { isSelected ->
    if (!isSelected) return@switchMap Observable.empty()
    Observables.combineLatest(
        prepareLimitOrderRows(boards, groupingSizes, preparationScheduler),
        orders
    ) { rows, activeOrders -> rows to activeOrders }
        .observeOn(renderScheduler)
}

private fun prepareLimitOrderRows(
    boards: Observable<Board>,
    groupingSizes: Observable<Long>,
    scheduler: Scheduler
): Observable<LimitOrderRows> =
    Observables.combineLatest(
        boards.throttleLatest(300, TimeUnit.MILLISECONDS, scheduler),
        groupingSizes
    ) { board, size -> board to size }
        .observeOn(scheduler)
        .map { (board, size) ->
            LimitOrderRows(
                asks = BoardType.Ask.groupBySize(board.asks.sortedBy { it.price }, size).take(50),
                bids = BoardType.Bid.groupBySize(board.bids.sortedByDescending { it.price }, size).take(50),
                groupingSize = size
            )
        }
