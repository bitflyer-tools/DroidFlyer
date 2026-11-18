package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.adapter.BoardType
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit

internal data class LimitOrderRows(val asks: List<Order>, val bids: List<Order>, val groupingSize: Long)

internal fun observeLimitOrderRows(
    boards: Observable<Board>,
    groupingSizes: Observable<Long>,
    scheduler: Scheduler = Schedulers.computation()
): Observable<LimitOrderRows> =
    Observables.combineLatest(
        boards.throttleLast(300, TimeUnit.MILLISECONDS, scheduler),
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
