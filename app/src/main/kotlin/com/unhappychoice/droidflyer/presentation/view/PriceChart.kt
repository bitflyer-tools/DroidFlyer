package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.unhappychoice.droidflyer.presentation.presenter.Candle
import com.unhappychoice.droidflyer.presentation.presenter.CandleSeries
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PriceChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var onCandleSelected: (Candle?) -> Unit = {}

    fun showSeries(series: CandleSeries) {
        this.series = series
        select(series.candles.last())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = series ?: return
        if (width <= 0 || plotBottom <= inset) return
        drawCandles(canvas, data)
        drawTimeLabels(canvas, data)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val data = series ?: return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> event.y <= plotBottom
            MotionEvent.ACTION_UP -> {
                if (event.x >= 0 && event.x < width && event.y in 0f..plotBottom) {
                    val slot = (event.x / slotWidth(data)).toLong()
                    select(data.candles.find { it.start == data.start + slot * data.interval.millis })
                    performClick()
                }
                true
            }
            else -> true
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private val density = resources.displayMetrics.density
    private val inset = 4 * density
    private val plotBottom get() = height - 24 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.scaledDensity
        strokeWidth = density
    }
    private var series: CandleSeries? = null
    private var selected: Candle? = null

    private fun select(candle: Candle?) {
        selected = candle
        onCandleSelected(candle)
        invalidate()
    }

    private fun slotWidth(data: CandleSeries): Float =
        width.toFloat() / ((data.end - data.start) / data.interval.millis)

    private fun drawCandles(canvas: Canvas, data: CandleSeries) {
        val low = data.candles.minOf { it.low }
        val range = data.candles.maxOf { it.high } - low
        val slotWidth = slotWidth(data)
        val halfBody = minOf(10 * density, slotWidth * 0.32f)
        fun y(price: Double): Float {
            val fraction = if (range == 0.0) 0.5 else (price - low) / range
            return inset + (plotBottom - 2 * inset) * (1 - fraction).toFloat()
        }

        data.candles.forEach { candle ->
            val x = ((candle.start - data.start) / data.interval.millis + 0.5f) * slotWidth
            if (selected == candle) {
                paint.color = 0x30FFFFFF
                canvas.drawRect(x - slotWidth / 2, 0f, x + slotWidth / 2, plotBottom, paint)
            }
            paint.color = if (candle.close >= candle.open) DefaultStyle.buyColor else DefaultStyle.sellColor
            paint.alpha = if (candle.partial) 140 else 255
            canvas.drawLine(x, y(candle.high), x, y(candle.low), paint)
            val top = minOf(y(candle.open), y(candle.close))
            canvas.drawRect(x - halfBody, top, x + halfBody, maxOf(top + density, y(candle.open), y(candle.close)), paint)
        }
    }

    private fun drawTimeLabels(canvas: Canvas, data: CandleSeries) {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        val baseline = plotBottom + 16 * density
        paint.color = DefaultStyle.accentColor
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(format.format(Date(data.start)), 0f, baseline, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(format.format(Date(data.end)), width.toFloat(), baseline, paint)
    }
}
