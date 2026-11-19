package com.unhappychoice.droidflyer.presentation

import android.app.Application
import android.content.res.Configuration
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ExecutionMessageTest {
    @Test
    fun distinguishesSpotAndCryptoCfdInEnglish() {
        assertExecutionMessages(Locale.US, "Executed")
    }

    @Test
    fun distinguishesSpotAndCryptoCfdInJapanese() {
        assertExecutionMessages(Locale.JAPAN, "約定しました")
    }

    private fun assertExecutionMessages(locale: Locale, prefix: String) {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        val context = app.createConfigurationContext(config)
        val spot = ChildOrderEvent(
            "BTC_JPY", "order-id", "acceptance-id", "2026-11-18T00:00:00Z",
            "EXECUTION", null, null, null, 1L, "BUY", 10000000L, 0.25, null, null
        )
        val cfd = spot.copy(productCode = "FX_BTC_JPY")

        assertEquals("$prefix BTC_JPY / BUY / 0.250000 / 10000000", context.executionMessage(spot))
        assertEquals("$prefix FX_BTC_JPY / BUY / 0.250000 / 10000000", context.executionMessage(cfd))
    }
}
