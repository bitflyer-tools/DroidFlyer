package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
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
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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
    private val pending = PublishSubject.create<ChildOrderAcceptance>()
    private val scheduler = TestScheduler()
    private val state = PrivateTradingState(apiClient, scheduler, Schedulers.trampoline())
    private val presenter = LimitOrderPresenter(state)
    private val scope = MortarScope.buildRootScope()
        .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
        .build("limit_order_test")

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(Schedulers.trampoline()) }
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending, PublishSubject.create())

        val themedContext = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val context = scope.createContext(themedContext)
        val view = LimitOrderView(context, null)
        FrameLayout(context).addView(view)
        presenter.takeView(view)
        presenter.amount.value = 0.001
    }

    @After
    fun tearDown() {
        scope.destroy()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun belowMinimumDoesNotSubmitLimitOrders() {
        presenter.amount.value = 0.0001

        assertFalse(presenter.canSubmitOrder)
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.SELL, 200)

        verify(apiClient, never()).sendChildOrder(any())
        assertFalse(presenter.isLoading.value)
    }

    @Test
    fun buyCanBeSubmittedAgainAfterSuccess() {
        val openSell = requireNotNull(presenter.prepareOrder(LimitOrderSide.SELL, 200))
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.BUY, 100)
        presenter.submitOrder(openSell)
        scheduler.triggerActions()

        verifyPendingOrder("BUY", 100)
        pending.onNext(ChildOrderAcceptance("test"))
        assertFalse(presenter.canSubmitOrder)
        pending.onComplete()

        assertTrue(presenter.canSubmitOrder)
        submit(LimitOrderSide.BUY, 101)
        verify(apiClient).sendChildOrder(request("BUY", 101))
    }

    @Test
    fun sellCanBeSubmittedAgainAfterFailure() {
        submit(LimitOrderSide.SELL, 200)
        submit(LimitOrderSide.SELL, 200)
        submit(LimitOrderSide.BUY, 100)

        verifyPendingOrder("SELL", 200)
        pending.onError(JsonParseException("Invalid acceptance ID"))

        assertTrue(presenter.canSubmitOrder)
        submit(LimitOrderSide.SELL, 201)
        verify(apiClient).sendChildOrder(request("SELL", 201))
    }

    @Test
    fun switchingAccountsInvalidatesOpenAndQueuedLimitOrders() {
        val queued = requireNotNull(presenter.prepareOrder(LimitOrderSide.BUY, 100))
        val open = requireNotNull(presenter.prepareOrder(LimitOrderSide.SELL, 200))
        val sessionEnd = open.order.sessionEnded.test()
        presenter.submitOrder(queued)
        assertFalse(presenter.canSubmitOrder)

        val newAccount = mock<APIClientV1>()
        whenever(newAccount.getPositions()).thenReturn(Observable.empty())
        whenever(newAccount.getCollateral()).thenReturn(Observable.empty())
        whenever(newAccount.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        whenever(newAccount.sendChildOrder(any())).thenReturn(Observable.empty())
        state.switchAccount(newAccount)
        sessionEnd.assertComplete()
        presenter.submitOrder(open)
        scheduler.triggerActions()

        verifyNoMoreInteractions(apiClient)
        verify(newAccount, never()).sendChildOrder(any())
        assertTrue(presenter.canSubmitOrder)
        submit(LimitOrderSide.SELL, 200)
        verify(newAccount).sendChildOrder(request("SELL", 200))
    }

    @Test
    fun submissionUsesTheAmountDisplayedInTheConfirmation() {
        val confirmation = requireNotNull(presenter.prepareOrder(LimitOrderSide.BUY, 100))
        presenter.amount.value = 0.02
        presenter.submitOrder(confirmation)
        scheduler.triggerActions()

        verifyPendingOrder("BUY", 100)
    }

    private fun submit(side: LimitOrderSide, price: Long) {
        presenter.prepareOrder(side, price)?.let(presenter::submitOrder)
        scheduler.triggerActions()
    }

    private fun verifyPendingOrder(side: String, price: Long) {
        assertFalse(presenter.canSubmitOrder)
        verify(apiClient).sendChildOrder(request(side, price))
        verifyNoMoreInteractions(apiClient)
    }

    private fun request(side: String, price: Long) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, 0.001)
}
