package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle

class PriceChart(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DefaultStyle.buyColor
        strokeWidth = 2 * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val path = Path()
    private var prices = emptyList<Double>()

    fun showPrices(prices: List<Double>) {
        this.prices = prices
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (prices.isEmpty()) return

        val inset = paint.strokeWidth
        val plotWidth = (width - 2 * inset).coerceAtLeast(0f)
        val plotHeight = (height - 2 * inset).coerceAtLeast(0f)
        val low = prices.min()
        val range = prices.max() - low

        path.rewind()
        prices.forEachIndexed { index, price ->
            val x = inset + plotWidth * index / prices.lastIndex.coerceAtLeast(1)
            val fraction = if (range == 0.0) 0.5 else (price - low) / range
            val y = inset + plotHeight * (1.0 - fraction).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (prices.size == 1) canvas.drawPoint(width / 2f, height / 2f, paint)
        else canvas.drawPath(path, paint)
    }
}
