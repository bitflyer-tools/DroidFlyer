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
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
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
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderViewTest {
    private val orderClient = mock<APIClientV1>()
    private val accountPreference = CryptoCfdAccountPreference(RuntimeEnvironment.getApplication())
    private val presenter = LimitOrderPresenter(orderClient, accountPreference)
    private val pending = PublishSubject.create<ChildOrderAcceptance>()
    private lateinit var status: CurrentStatusService

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        whenever(orderClient.sendChildOrder(any())).thenReturn(pending, Observable.never())
        val statusClient = mock<APIClientV1>()
        whenever(statusClient.getPositions()).thenReturn(Observable.empty())
        whenever(statusClient.getCollateral()).thenReturn(Observable.empty())
        whenever(statusClient.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        whenever(statusClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        status = CurrentStatusService(statusClient, Observable.never(), Observable.never(), Observable.never())
    }

    @After
    fun tearDown() {
        status.dispose()
        RxJavaPlugins.reset()
    }

    @Test
    fun confirmationsKeepTheirTermsAndPendingSubmissionBlocksAnOpenDialog() {
        withLimitView { view ->
            presenter.amount.value = 0.01
            val buy = view.confirmation(R.id.bidList, R.string.buy, 100, 0.01)
            presenter.amount.value = 0.02
            val sell = view.confirmation(R.id.askList, R.string.sell, 200, 0.02)
            presenter.amount.value = 0.0

            buy.accept()
            presenter.amount.value = 0.05
            sell.accept()
            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
            verifyNoMoreInteractions(orderClient)
            pending.onComplete()
            shadowOf(Looper.getMainLooper()).idle()

            presenter.amount.value = 0.03
            val latestSell = view.confirmation(R.id.askList, R.string.sell, 201, 0.03)
            presenter.amount.value = 0.04
            latestSell.accept()
            verify(orderClient).sendChildOrder(request("SELL", 201, 0.03))
            verifyNoMoreInteractions(orderClient)
        }
    }

    @Test
    fun limitConfirmationAndSubmissionUseTheCurrentAccountMaximum() {
        withLimitView { view ->
            presenter.amount.value = 10.0
            val individual = view.confirmation(R.id.bidList, R.string.buy, 100, 10.0)
            presenter.amount.value = 10.001
            view.clickRow(R.id.askList, 200)
            assertSame(individual, ShadowDialog.getLatestDialog())

            accountPreference.isCorporate = true
            presenter.amount.value = 40.0
            val corporate = view.confirmation(R.id.askList, R.string.sell, 200, 40.0)
            presenter.amount.value = 41.0
            view.clickRow(R.id.bidList, 100)
            assertSame(corporate, ShadowDialog.getLatestDialog())

            accountPreference.isCorporate = false
            corporate.accept()
            verifyNoInteractions(orderClient)

            individual.accept()
            verify(orderClient).sendChildOrder(request("BUY", 100, 10.0))
            pending.onComplete()
            shadowOf(Looper.getMainLooper()).idle()

            accountPreference.isCorporate = true
            presenter.amount.value = 40.0
            view.confirmation(R.id.askList, R.string.sell, 201, 40.0).accept()
            verify(orderClient).sendChildOrder(request("SELL", 201, 40.0))
            verifyNoMoreInteractions(orderClient)
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
            verifyNoInteractions(orderClient)
            window.tap(button)
            verify(orderClient).sendChildOrder(request("BUY", 100, 0.01))
            verifyNoMoreInteractions(orderClient)
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

    private fun LimitOrderView.clickRow(list: Int, price: Long) {
        val adapter = findViewById<RecyclerView>(list).adapter as BoardAdapter
        adapter.clickItems.onNext(Order(price, 1.0))
    }

    private fun LimitOrderView.confirmation(list: Int, label: Int, price: Long, size: Double): AlertDialog {
        clickRow(list, price)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()
        assertEquals("${context.getString(label)} / $price / $size", message)
        return dialog
    }

    private fun AlertDialog.accept() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
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
