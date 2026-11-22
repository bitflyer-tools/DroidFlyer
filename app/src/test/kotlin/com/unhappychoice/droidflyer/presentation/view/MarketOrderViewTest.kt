package com.unhappychoice.droidflyer.presentation.view

import android.Manifest
import android.content.DialogInterface
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.MainActivityNavigationTest
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.presentation.presenter.MarketOrderPresenter
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [MainActivityNavigationTest.DisconnectedSocket::class],
    instrumentedPackages = ["io.socket.client"]
)
class MarketOrderViewTest {
    private val client = mock<APIClientV1>()

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
        MainActivityNavigationTest.DisconnectedSocket.connections.clear()
    }

    @Test
    fun obscuredMarketButtonsDoNotSubmitAndClearTapsSubmitOncePerSide() {
        withMarketView { activity, view, presenter, _ ->
            presenter.amount.value = 0.5
            val buy = view.findViewById<View>(R.id.buyButton)
            val sell = view.findViewById<View>(R.id.sellButton)

            activity.window.tapWithObscuration(buy)
            activity.window.tapWithObscuration(sell)
            verify(client, never()).sendChildOrder(any())

            activity.window.tap(buy)
            activity.window.tap(sell)
            verify(client).sendChildOrder(request("BUY"))
            verify(client).sendChildOrder(request("SELL"))
            verify(client, times(2)).sendChildOrder(any())
        }
    }

    @Test
    fun obscuredCloseConfirmationDoesNotSubmitAndClearTapSubmitsOnce() {
        val positions = listOf(Position("FX_BTC_JPY", "BUY", 1000000L, 0.5, 0.0, 0.0, 0.0, 1L, 0.0))

        withMarketView { activity, view, _, status ->
            whenever(client.getPositions()).thenReturn(Observable.just(positions))
            whenever(client.getChildOrders("FX_BTC_JPY", null, "JRF-accepted")).thenReturn(Observable.never())
            status.position.value = positions
            activity.window.tap(view.findViewById(R.id.clearButton))
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val window = requireNotNull(dialog.window)

            window.tapWithObscuration(button)
            verify(client, never()).sendChildOrder(any())
            window.tap(button)
            verify(client).sendChildOrder(request("SELL", "IOC"))
            verify(client).sendChildOrder(any())
        }
    }

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
        withMarketView { activity, view, _, status ->
            status.position.value = listOf(Position("FX_BTC_JPY", "BUY", 100, 5.0, 0.0, 0.0, 0.0, 1, 0.0))
            activity.window.tap(view.findViewById(R.id.clearButton))

            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<TextView>(android.R.id.message)
            assertEquals("$summary\n\n$disclosure", message?.text.toString())
        }
    }

    private fun withMarketView(test: (MainActivity, MarketOrderView, MarketOrderPresenter, CurrentStatusService) -> Unit) {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val paused = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { paused }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
            whenever(client.getPositions()).thenReturn(Observable.empty())
            whenever(client.getCollateral()).thenReturn(Observable.empty())
            whenever(client.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
            whenever(client.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
            whenever(client.sendChildOrder(any())).thenReturn(Observable.just(ChildOrderAcceptance("JRF-accepted")))
            val status = CurrentStatusService(client, Observable.never(), Observable.never(), Observable.never())
            val presenter = MarketOrderPresenter(status) {}
            val activity = controller.get()
            val view = activity.layoutInflater.inflate(R.layout.market_order_view, null) as MarketOrderView
            view.inject(DI {
                bindInstance { presenter }
                bindInstance { status }
            })
            activity.binding.containerView.addView(view)
            try {
                test(activity, view, presenter, status)
            } finally {
                activity.binding.containerView.removeView(view)
                status.dispose()
            }
        }
    }

    private fun request(side: String, timeInForce: String = "GTC") =
        SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.5, timeInForce = timeInForce)
}
