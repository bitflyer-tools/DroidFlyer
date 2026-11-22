package com.unhappychoice.droidflyer.presentation.view

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.rxkotlin.Observables
import io.reactivex.schedulers.Schedulers

private const val DISPLAY_DEPTH = 50

internal data class LimitOrderRows(val asks: List<Order>, val bids: List<Order>, val groupingSize: Long)

/** Expects order-book snapshots with ascending asks and descending bids. */
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
        boards,
        groupingSizes
    ) { board, size -> board to size }
        .observeOn(scheduler)
        .map { (board, size) ->
            LimitOrderRows(
                asks = board.asks.groupVisibleLevels { it.ceiledPrice(size) },
                bids = board.bids.groupVisibleLevels { it.flooredPrice(size) },
                groupingSize = size
            )
        }

private inline fun List<Order>.groupVisibleLevels(bucketPrice: (Order) -> Long): List<Order> {
    val levels = iterator()
    if (!levels.hasNext()) return emptyList()

    val first = levels.next()
    val rows = ArrayList<Order>(DISPLAY_DEPTH)
    var price = bucketPrice(first)
    var quantity = 0.0 + first.size
    while (levels.hasNext()) {
        val level = levels.next()
        val nextPrice = bucketPrice(level)
        if (nextPrice != price) {
            rows.add(Order(price, quantity))
            if (rows.size == DISPLAY_DEPTH) return rows
            price = nextPrice
            quantity = 0.0
        }
        quantity += level.size
    }
    rows.add(Order(price, quantity))
    return rows
}
