package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.adapter.activeOrder
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
    private val acceptedOrder = activeOrder("BUY", 100, 0.001).copy(
        productCode = "FX_BTC_JPY", size = 0.001, executedSize = 0.0
    )
    private val accountPreference = CryptoCfdAccountPreference(RuntimeEnvironment.getApplication())
    private val presenter by lazy { LimitOrderPresenter(state, accountPreference) {}.also(::attachView) }
    private var status: CurrentStatusService? = null
    private val scope = MortarScope.buildRootScope()
        .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
        .build("limit_order_test")

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending, PublishSubject.create())
    }

    private fun attachView(presenter: LimitOrderPresenter) {
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
        status?.dispose()
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

    @Test
    fun acceptanceRefreshesActiveOrdersImmediately() {
        val initialOrders = PublishSubject.create<List<ChildOrder>>()
        val status = submitWithStatus(initialOrders)
        initialOrders.onComplete()
        assertTrue(status.orders.value.isEmpty())

        pending.onNext(ChildOrderAcceptance(acceptedOrder.childOrderAcceptanceId))

        assertEquals(listOf(acceptedOrder), status.orders.value)
    }

    @Test
    fun acceptanceQueuesAnActiveOrderFetchAfterAnInFlightRefresh() {
        val initialOrders = PublishSubject.create<List<ChildOrder>>()
        val status = submitWithStatus(initialOrders)

        pending.onNext(ChildOrderAcceptance(acceptedOrder.childOrderAcceptanceId))
        pending.onComplete()
        verify(apiClient).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(status.orders.value.isEmpty())

        initialOrders.onNext(emptyList())
        initialOrders.onComplete()

        verify(apiClient, times(2)).getChildOrders("FX_BTC_JPY", count = 500)
        assertEquals(listOf(acceptedOrder), status.orders.value)
    }

    @Test
    fun failedSubmissionDoesNotRefreshActiveOrders() {
        val initialOrders = PublishSubject.create<List<ChildOrder>>()
        val status = submitWithStatus(initialOrders)

        pending.onError(JsonParseException("Invalid acceptance ID"))
        initialOrders.onComplete()

        verify(apiClient).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(status.orders.value.isEmpty())
    }

    private fun submitWithStatus(initialOrders: Observable<List<ChildOrder>>): CurrentStatusService {
        whenever(apiClient.getPositions()).thenReturn(Observable.empty())
        whenever(apiClient.getCollateral()).thenReturn(Observable.empty())
        whenever(apiClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(apiClient.getChildOrders("FX_BTC_JPY", count = 500))
            .thenReturn(initialOrders, Observable.just(listOf(acceptedOrder)))
        val status = CurrentStatusService(apiClient, Observable.never(), Observable.never(), Observable.never())
        this.status = status
        val presenter = LimitOrderPresenter(status.privateState, accountPreference, status::updateStatus)
        attachView(presenter)
        presenter.submitOrder(requireNotNull(presenter.prepareOrder(LimitOrderSide.BUY, 100)))
        return status
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
