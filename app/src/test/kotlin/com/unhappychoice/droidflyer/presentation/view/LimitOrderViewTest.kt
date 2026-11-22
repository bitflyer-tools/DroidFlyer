package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.presentation.adapter.BoardAdapter
import com.unhappychoice.droidflyer.presentation.presenter.LimitOrderPresenter
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderViewTest {
    private val orderClient = mockAccount()
    private lateinit var presenter: LimitOrderPresenter
    private val pending = PublishSubject.create<ChildOrderAcceptance>()
    private lateinit var status: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        whenever(orderClient.sendChildOrder(any())).thenReturn(pending, Observable.never())
        status = CurrentStatusService(mockAccount(), Observable.never(), Observable.never(), Observable.never(), orderClient)
        presenter = LimitOrderPresenter(status.privateState)
    }

    @After
    fun tearDown() {
        status.dispose()
        RxJavaPlugins.reset()
    }

    @Test
    fun confirmationsKeepTheirTermsAndAreDismissedOnAccountSwitch() {
        withLimitView { view ->
            presenter.amount.value = 0.01
            val previousSell = view.confirmation(R.id.askList, R.string.sell, 200, 0.01)
            val buy = view.confirmation(R.id.bidList, R.string.buy, 100, 0.01)
            assertFalse(previousSell.isShowing)
            presenter.amount.value = 0.0
            buy.accept()

            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
            pending.onComplete()
            shadowOf(Looper.getMainLooper()).idle()

            presenter.amount.value = 0.03
            val staleSell = view.confirmation(R.id.askList, R.string.sell, 201, 0.03)
            val newAccount = mockAccount()
            whenever(newAccount.sendChildOrder(any())).thenReturn(Observable.empty())
            status.switchAccount(newAccount)
            assertFalse(staleSell.isShowing)
            verify(newAccount, never()).sendChildOrder(any())

            val latestSell = view.confirmation(R.id.askList, R.string.sell, 202, 0.03)
            presenter.amount.value = 0.04
            latestSell.accept()
            verify(newAccount).sendChildOrder(request("SELL", 202, 0.03))
            verify(orderClient, times(1)).sendChildOrder(any())
        }
    }

    @Test
    @Config(sdk = [30])
    fun obscuredConfirmationDoesNotSubmitAndClearTapSubmitsOnce() {
        withLimitView { view ->
            presenter.amount.value = 0.01
            val dialog = view.confirmation(R.id.bidList, R.string.buy, 100, 0.01)
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            val window = requireNotNull(dialog.window)

            window.tapWithObscuration(button)
            verify(orderClient, never()).sendChildOrder(any())
            window.tap(button)
            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
        }
    }

    private fun withLimitView(test: (LimitOrderView) -> Unit) {
        Robolectric.buildActivity(LimitOrderHost::class.java).setup().use { controller ->
            val host = controller.get()
            host.setTheme(R.style.AppTheme)
            val view = host.layoutInflater.inflate(R.layout.limit_order_view, null) as LimitOrderView
            view.inject(DI {
                bindInstance { presenter }
                bindInstance { status }
            })
            host.setContentView(view)
            test(view)
        }
    }

    private fun LimitOrderView.confirmation(list: Int, label: Int, price: Long, size: Double): AlertDialog {
        val adapter = findViewById<RecyclerView>(list).adapter as BoardAdapter
        adapter.clickItems.onNext(Order(price, 1.0))
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertEquals("${context.getString(label)} / $price / $size", message)
        return dialog
    }

    private fun AlertDialog.accept() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun mockAccount() = mock<APIClientV1>().also {
        whenever(it.getPositions()).thenReturn(Observable.empty())
        whenever(it.getCollateral()).thenReturn(Observable.empty())
        whenever(it.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        whenever(it.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
    }

    private fun request(side: String, price: Long, size: Double) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, size)

    class LimitOrderHost : Activity() {
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("limit_order_view_test")
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        override fun onDestroy() {
            super.onDestroy()
            scope.destroy()
        }
    }
}
