package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyNoMoreInteractions
import com.nhaarman.mockito_kotlin.whenever
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LimitOrderPresenterTest {
    private val apiClient = mock<APIClientV1>()
    private val pending = PublishSubject.create<Map<String, Any>>()
    private val scheduler = TestScheduler()
    private val state = PrivateTradingState(apiClient, scheduler, Schedulers.trampoline())
    private val presenter = LimitOrderPresenter(state)
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
        submit("BUY", 100)
        submit("BUY", 100)
        submit("SELL", 200)

        verifyPendingOrder("BUY", 100)
        pending.onNext(mapOf("child_order_acceptance_id" to "test"))
        assertFalse(presenter.canSubmitOrder)
        pending.onComplete()

        assertTrue(presenter.canSubmitOrder)
        submit("BUY", 101)
        verify(apiClient).sendChildOrder(request("BUY", 101))
    }

    @Test
    fun sellCanBeSubmittedAgainAfterFailure() {
        submit("SELL", 200)
        submit("SELL", 200)
        submit("BUY", 100)

        verifyPendingOrder("SELL", 200)
        pending.onError(IllegalStateException("Order failed"))

        assertTrue(presenter.canSubmitOrder)
        submit("SELL", 201)
        verify(apiClient).sendChildOrder(request("SELL", 201))
    }

    @Test
    fun switchingAccountsInvalidatesOpenAndQueuedLimitOrders() {
        val queued = requireNotNull(presenter.prepareOrder(100, "BUY"))
        val open = requireNotNull(presenter.prepareOrder(200, "SELL"))
        val sessionEnd = open.sessionEnded.test()
        presenter.submitOrder(queued)
        assertFalse(presenter.canSubmitOrder)

        val newAccount = mock<APIClientV1>()
        whenever(newAccount.getPositions()).thenReturn(Observable.empty())
        whenever(newAccount.getCollateral()).thenReturn(Observable.empty())
        whenever(newAccount.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(newAccount.sendChildOrder(any())).thenReturn(Observable.empty())
        state.switchAccount(newAccount)
        sessionEnd.assertComplete()
        presenter.submitOrder(open)
        scheduler.triggerActions()

        verifyNoMoreInteractions(apiClient)
        verify(newAccount, never()).sendChildOrder(any())
        assertTrue(presenter.canSubmitOrder)
        submit("SELL", 200)
        verify(newAccount).sendChildOrder(request("SELL", 200))
    }

    @Test
    fun submissionUsesTheAmountDisplayedInTheConfirmation() {
        val confirmation = requireNotNull(presenter.prepareOrder(100, "BUY"))
        presenter.amount.value = 0.02
        presenter.submitOrder(confirmation)
        scheduler.triggerActions()

        verifyPendingOrder("BUY", 100)
    }

    private fun submit(side: String, price: Long) {
        presenter.prepareOrder(price, side)?.let(presenter::submitOrder)
        scheduler.triggerActions()
    }

    private fun verifyPendingOrder(side: String, price: Long) {
        assertFalse(presenter.canSubmitOrder)
        verify(apiClient).sendChildOrder(request(side, price))
        verifyNoMoreInteractions(apiClient)
    }

    private fun request(side: String, price: Long) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, 0.01)
}
