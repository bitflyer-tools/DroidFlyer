package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderPresenterTest {
    private val apiClient = mock<APIClientV1>()
    private val pending = PublishSubject.create<Map<String, Any>>()
    private val presenter = LimitOrderPresenter(apiClient)
    private val scope = MortarScope.buildRootScope()
        .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
        .build("limit_order_test")

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending, PublishSubject.create())

        val themedContext = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val context = scope.createContext(themedContext)
        val view = LimitOrderView(context, null)
        FrameLayout(context).addView(view)
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
        presenter.buy(100)
        presenter.buy(100)
        presenter.sell(200)

        verifyPendingOrder("BUY", 100)
        pending.onNext(mapOf("child_order_acceptance_id" to "test"))
        assertFalse(presenter.canSubmitOrder)
        pending.onComplete()

        assertTrue(presenter.canSubmitOrder)
        presenter.buy(101)
        verify(apiClient).sendChildOrder(request("BUY", 101))
    }

    @Test
    fun sellCanBeSubmittedAgainAfterFailure() {
        presenter.sell(200)
        presenter.sell(200)
        presenter.buy(100)

        verifyPendingOrder("SELL", 200)
        pending.onError(IllegalStateException("Order failed"))

        assertTrue(presenter.canSubmitOrder)
        presenter.sell(201)
        verify(apiClient).sendChildOrder(request("SELL", 201))
    }

    private fun verifyPendingOrder(side: String, price: Long) {
        assertFalse(presenter.canSubmitOrder)
        verify(apiClient).sendChildOrder(request(side, price))
        verifyNoMoreInteractions(apiClient)
    }

    private fun request(side: String, price: Long) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, 0.01)
}
