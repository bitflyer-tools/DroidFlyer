package com.unhappychoice.droidflyer.presentation.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.unhappychoice.droidflyer.presentation.presenter.Candle
import com.unhappychoice.droidflyer.presentation.presenter.CandleInterval
import com.unhappychoice.droidflyer.presentation.presenter.CandleSeries
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PriceChartTest {
    @Test
    fun drawsRisingAndFallingCandlesWithAnEmptySlotBetweenThem() {
        val interval = CandleInterval.ONE_MINUTE
        val rising = Candle(0L, 100.0, 110.0, 90.0, 105.0, partial = false)
        val falling = Candle(2 * interval.millis, 105.0, 110.0, 90.0, 100.0, partial = false)
        val chart = PriceChart(RuntimeEnvironment.getApplication(), null).apply {
            layout(0, 0, 300, 224)
            showSeries(CandleSeries(interval, 0L, 3 * interval.millis, listOf(rising, falling)))
        }

        val bitmap = Bitmap.createBitmap(chart.width, chart.height, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))

        assertEquals(DefaultStyle.buyColor, bitmap.getPixel(55, 75))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(150, 75))
        assertEquals(DefaultStyle.sellColor, bitmap.getPixel(255, 75))
    }
}
