package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.Observable
import io.reactivex.Scheduler
import java.util.Collections
import java.util.TreeMap
import java.util.concurrent.TimeUnit

internal enum class BoardSource { SNAPSHOT, DELTA, REST, DISCONNECT }

internal data class BoardUpdate(val board: Board, val source: BoardSource, val requestRevision: Long? = null) {
    companion object {
        fun disconnected() = BoardUpdate(Board(0.0, emptyList(), emptyList()), BoardSource.DISCONNECT)
    }
}

internal fun boardUpdates(snapshots: Observable<Board>, deltas: Observable<Board>): Observable<BoardUpdate> =
    Observable.merge(
        snapshots.map { BoardUpdate(it, BoardSource.SNAPSHOT) },
        deltas.map { BoardUpdate(it, BoardSource.DELTA) }
    )

internal class OrderBookFallback {
    private var revisionCounter = 0L
    // Cold start has no full book yet, so deltas must wait until a REST or realtime snapshot seeds one.
    private var awaitingBaseline = true
    @Volatile private var revision: Long? = 0L

    // Calls arrive through the serialized merged board stream.
    fun acceptUpdate(update: BoardUpdate): Boolean {
        when (update.source) {
            BoardSource.SNAPSHOT -> {
                awaitingBaseline = false
                revision = null
            }
            BoardSource.DISCONNECT -> {
                awaitingBaseline = true
                revision = ++revisionCounter
            }
            BoardSource.DELTA -> {
                if (awaitingBaseline) return false
                if (revision != null) revision = ++revisionCounter
            }
            BoardSource.REST -> if (isCurrent(update)) awaitingBaseline = false
        }
        return true
    }

    fun requestRevision(): Long? = revision

    fun isCurrent(update: BoardUpdate): Boolean =
        update.requestRevision?.let { it == revision } ?: true
}

internal sealed class OrderBookEvent {
    data class Quotes(val bestAsk: Long, val bestBid: Long) : OrderBookEvent()
    data class View(val board: Board) : OrderBookEvent()
}

internal fun observeOrderBook(
    updates: Observable<BoardUpdate>,
    serialScheduler: Scheduler,
    isCurrent: (BoardUpdate) -> Boolean = { true }
): Observable<OrderBookEvent> = Observable.defer {
    val book = OrderBook()
    updates.observeOn(serialScheduler)
        .filter { isCurrent(it) && book.apply(it) }
        .publish { applied ->
            Observable.merge<OrderBookEvent>(
                applied.map { book.quotes() }.distinctUntilChanged(),
                applied.filter { it.source == BoardSource.DISCONNECT }
                    .map { OrderBookEvent.View(book.view()) },
                applied.filter { it.source != BoardSource.DISCONNECT }
                    .sample(300, TimeUnit.MILLISECONDS, serialScheduler, true)
                    .map { OrderBookEvent.View(book.view()) }
            )
        }
}

private class OrderBook {
    fun apply(update: BoardUpdate): Boolean {
        if (update.source == BoardSource.REST && hasRealtimeSnapshot) return false
        if (update.source == BoardSource.SNAPSHOT) hasRealtimeSnapshot = true
        if (update.source == BoardSource.DISCONNECT) hasRealtimeSnapshot = false
        if (update.source != BoardSource.DELTA) {
            midPrice = update.board.midPrice
            bids.clear()
            asks.clear()
        }
        bids.applyLevels(update.board.bids)
        asks.applyLevels(update.board.asks)
        return true
    }

    fun quotes() = OrderBookEvent.Quotes(
        asks.firstEntry()?.key ?: 0L,
        bids.firstEntry()?.key ?: 0L
    )

    fun view() = Board(midPrice, bids.immutableLevels(), asks.immutableLevels())

    private val bids = TreeMap<Long, Order>(reverseOrder())
    private val asks = TreeMap<Long, Order>()
    private var midPrice = 0.0
    private var hasRealtimeSnapshot = false

    private fun TreeMap<Long, Order>.applyLevels(levels: List<Order>) {
        levels.forEach { level ->
            if (level.size == 0.0) remove(level.price) else put(level.price, level)
        }
    }

    private fun TreeMap<Long, Order>.immutableLevels(): List<Order> =
        Collections.unmodifiableList(ArrayList(values))
}
