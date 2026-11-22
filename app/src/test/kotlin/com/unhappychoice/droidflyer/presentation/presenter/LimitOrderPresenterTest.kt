package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.presentation.adapter.activeOrder
import com.unhappychoice.droidflyer.presentation.view.LimitOrderView
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
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
import org.mockito.Mockito.times
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
    private val statusClient = mock<APIClientV1>()
    private val initialOrders = PublishSubject.create<List<ChildOrder>>()
    private val acceptedOrder = activeOrder("BUY", 100, 0.001).copy(
        productCode = "FX_BTC_JPY", size = 0.001, executedSize = 0.0
    )
    private lateinit var status: CurrentStatusService
    private lateinit var presenter: LimitOrderPresenter
    private val scope = MortarScope.buildRootScope()
        .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
        .build("limit_order_test")

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        whenever(apiClient.sendChildOrder(any())).thenReturn(pending, PublishSubject.create())

        whenever(statusClient.getPositions()).thenReturn(Observable.empty())
        whenever(statusClient.getCollateral()).thenReturn(Observable.empty())
        whenever(statusClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        whenever(statusClient.getChildOrders("FX_BTC_JPY", count = 500))
            .thenReturn(initialOrders, Observable.just(listOf(acceptedOrder)))
        status = CurrentStatusService(statusClient, Observable.never(), Observable.never(), Observable.never())
        presenter = LimitOrderPresenter(apiClient, status)

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
        status.dispose()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun belowMinimumDoesNotSubmitLimitOrders() {
        presenter.amount.value = 0.0001

        assertFalse(presenter.canSubmitOrder)
        presenter.amount.value = 0.001
        submit(LimitOrderSide.BUY, 100, size = 0.0001)
        submit(LimitOrderSide.SELL, 200, size = 0.0001)

        verify(apiClient, never()).sendChildOrder(any())
        assertFalse(presenter.isLoading.value)
    }

    @Test
    fun buyAcceptanceRefreshesActiveOrdersImmediatelyAndAllowsResubmission() {
        initialOrders.onComplete()
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.BUY, 100)
        submit(LimitOrderSide.SELL, 200)

        verifyPendingOrder("BUY", 100)
        assertTrue(status.orders.value.isEmpty())
        pending.onNext(ChildOrderAcceptance(acceptedOrder.childOrderAcceptanceId))
        assertEquals(listOf(acceptedOrder), status.orders.value)
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
        initialOrders.onComplete()

        verify(statusClient).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(status.orders.value.isEmpty())

        assertTrue(presenter.canSubmitOrder)
        submit(LimitOrderSide.SELL, 201)
        verify(apiClient).sendChildOrder(request("SELL", 201))
    }

    @Test
    fun acceptanceQueuesAnActiveOrderFetchAfterAnInFlightRefresh() {
        submit(LimitOrderSide.BUY, 100)
        pending.onNext(ChildOrderAcceptance(acceptedOrder.childOrderAcceptanceId))
        pending.onComplete()

        verify(statusClient).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(status.orders.value.isEmpty())

        initialOrders.onNext(emptyList())
        initialOrders.onComplete()

        verify(statusClient, times(2)).getChildOrders("FX_BTC_JPY", count = 500)
        assertEquals(listOf(acceptedOrder), status.orders.value)
    }

    private fun submit(side: LimitOrderSide, price: Long, size: Double = 0.001) =
        presenter.submitOrder(LimitOrderTerms(side, price, size))

    private fun verifyPendingOrder(side: String, price: Long) {
        assertFalse(presenter.canSubmitOrder)
        verify(apiClient).sendChildOrder(request(side, price))
        verifyNoMoreInteractions(apiClient)
    }

    private fun request(side: String, price: Long) =
        SendChildOrderRequest("FX_BTC_JPY", "LIMIT", side, price, 0.001)
}
