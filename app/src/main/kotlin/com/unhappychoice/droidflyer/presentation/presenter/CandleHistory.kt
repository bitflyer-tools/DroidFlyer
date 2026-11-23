package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class CandleInterval(val minutes: Int) {
    ONE_MINUTE(1),
    FIVE_MINUTES(5);

    val millis = minutes * 60_000L

    fun startOf(time: Long): Long = time - (time % millis + millis) % millis
}

data class Candle(
    val start: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val partial: Boolean
)

data class CandleSeries(
    val interval: CandleInterval,
    val start: Long,
    val end: Long,
    val candles: List<Candle>
)

class CandleHistory internal constructor(
    trades: List<ChartTrade>,
    private val windowStart: Long,
    private val limited: Boolean
) {
    fun series(interval: CandleInterval): CandleSeries = seriesByInterval.getValue(interval)

    private val seriesByInterval = CandleInterval.entries.associateWith { interval ->
        val start = if (limited) interval.startOf(trades.first().time) else windowStart
        val end = interval.startOf(trades.last().time) + interval.millis
        val candles = trades.groupBy { interval.startOf(it.time) }.map { (bucket, executions) ->
            Candle(
                bucket, executions.first().price, executions.maxOf { it.price },
                executions.minOf { it.price }, executions.last().price, limited && bucket == start
            )
        }
        CandleSeries(interval, start, end, candles)
    }
}

internal data class ChartTrade(val id: Long, val price: Double, val time: Long, val nanos: Int) {
    companion object {
        private val timestamp = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(?:Z|\+00:00)?""")

        fun parsePage(page: List<ExecutionPrice>): List<ChartTrade> {
            val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            return page.map { trade ->
                require(trade.price.isFinite() && trade.price > 0)
                val parts = requireNotNull(trade.execDate?.let(timestamp::matchEntire))
                val seconds = requireNotNull(utc.parse(parts.groupValues[1])).time
                val nanos = parts.groupValues[2].padEnd(9, '0').toInt()
                ChartTrade(trade.id, trade.price, seconds + nanos / 1_000_000, nanos)
            }
        }
    }
}
