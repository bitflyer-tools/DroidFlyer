package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import io.reactivex.Observable
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MarketOrderViewTest {
    @Test
    @Config(qualifiers = "en")
    fun closeWithinOrderLimitDisclosesFollowUpOrdersInEnglish() {
        assertCloseConfirmation(
            "Sell / 5.0",
            "The app checks the remaining position after each market order and may submit additional market orders " +
                "if an order is partially filled or the position exceeds the order size limit."
        )
    }

    @Test
    @Config(qualifiers = "ja")
    fun closeWithinOrderLimitDisclosesFollowUpOrdersInJapanese() {
        assertCloseConfirmation(
            "売り / 5.0",
            "成行注文のたびに残りの建玉を確認します。" +
                "注文が一部だけ約定した場合や、建玉の数量が1回の注文上限を超える場合は、追加の成行注文を出すことがあります。"
        )
    }

    private fun assertCloseConfirmation(summary: String, disclosure: String) {
        val client = mock<APIClientV1>()
        whenever(client.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        val status = CurrentStatusService(
            client, Observable.never(), Observable.never(), Observable.never(), privateClient = null
        )
        try {
            Robolectric.buildActivity(MarketOrderHost::class.java).setup().use { controller ->
                val host = controller.get()
                host.setTheme(R.style.AppTheme)
                val view = host.layoutInflater.inflate(R.layout.market_order_view, null) as MarketOrderView
                view.inject(DI {
                    bindInstance { MarketOrderPresenter(client, status) }
                    bindInstance { status }
                })
                host.setContentView(view)
                status.position.value = listOf(Position("FX_BTC_JPY", "BUY", 100, 5.0, 0.0, 0.0, 0.0, 1, 0.0))

                view.findViewById<View>(R.id.clearButton).performClick()

                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertEquals("$summary\n\n$disclosure", dialog.findViewById<TextView>(android.R.id.message)?.text.toString())
            }
        } finally {
            status.dispose()
        }
    }

    class MarketOrderHost : Activity() {
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("market_order_view_test")
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        override fun onDestroy() {
            super.onDestroy()
            scope.destroy()
        }
    }
}
