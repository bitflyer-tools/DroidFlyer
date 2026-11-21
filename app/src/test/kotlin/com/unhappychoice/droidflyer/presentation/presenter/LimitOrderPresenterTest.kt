package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import com.google.android.material.R as MaterialR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderPresenterTest {
    private val apiClient = mock<APIClientV1>()
    private val pending = PublishSubject.create<ChildOrderAcceptance>()
    private val presenter = LimitOrderPresenter(apiClient)
    private val scope = MortarScope.buildRootScope()
        .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
        .build("limit_order_test")

    private lateinit var container: FrameLayout

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending, PublishSubject.create())

        val themedContext = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val context = scope.createContext(themedContext)
        val view = LimitOrderView(context, null)
        container = FrameLayout(context).apply { addView(view) }
        presenter.takeView(view)
        presenter.amount.value = 0.01
    }

    @After
    fun tearDown() {
        scope.destroy()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun buyCanBeSubmittedAgainAfterSuccess() {
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.SELL, 200)

        verifyPendingOrder("BUY", 100)
        pending.onNext(ChildOrderAcceptance("test"))
        assertEquals(container.context.getString(R.string.ordered_buy, 0.01, 100L), snackbarMessage())
        assertFalse(presenter.canSubmitOrder)
        pending.onComplete()

        assertTrue(presenter.canSubmitOrder)
        submit(LimitOrderSide.BUY, 101)
        verify(apiClient).sendChildOrder(request("BUY", 101))
    }

    @Test
    fun rejectedSellShowsFailureAndRestoresControls() {
        submit(LimitOrderSide.SELL, 200)
        submit(LimitOrderSide.SELL, 200)
        submit(LimitOrderSide.BUY, 100)

        verifyPendingOrder("SELL", 200)
        assertNull(snackbarMessage())
        pending.onError(HttpException(Response.error<Any>(400, "".toResponseBody())))

        assertTrue(presenter.canSubmitOrder)
        assertEquals(container.context.getString(R.string.limit_order_failed), snackbarMessage())
        submit(LimitOrderSide.SELL, 201)
        verify(apiClient).sendChildOrder(request("SELL", 201))
    }

    private fun snackbarMessage(): String? {
        shadowOf(Looper.getMainLooper()).idle()
        return container.findViewById<TextView>(MaterialR.id.snackbar_text)?.text?.toString()
    }

    private fun submit(side: LimitOrderSide, price: Long) =
        presenter.submitOrder(LimitOrderTerms(side, price, 0.01))

    private fun verifyPendingOrder(side: String, price: Long) {
        assertFalse(presenter.canSubmitOrder)
        verify(apiClient).sendChildOrder(request(side, price))
        verifyNoMoreInteractions(apiClient)
    }

    private fun request(side: String, price: Long) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, 0.01)
}
