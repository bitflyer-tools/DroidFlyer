package com.unhappychoice.droidflyer.presentation.presenter

import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import retrofit2.HttpException
import retrofit2.Response

class MarketOrderPresenterTest {
    private val scheduler = TestScheduler()
    private val preferenceBackedClient = Mockito.mock(APIClientV1::class.java)
    private val apiClient = Mockito.mock(APIClientV1::class.java)
    private val closePositionResponse = PublishSubject.create<List<Position>>()
    private val acceptedResponse = ChildOrderAcceptance("JRF-accepted")
    private val messages = mutableListOf<Int>()
    private lateinit var status: CurrentStatusService
    private lateinit var presenter: MarketOrderPresenter

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }

        Mockito.`when`(apiClient.getCollateral()).thenReturn(Observable.empty())
        Mockito.`when`(preferenceBackedClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        Mockito.`when`(apiClient.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        Mockito.`when`(apiClient.getPositions()).thenReturn(Observable.empty())
        status = CurrentStatusService(
            preferenceBackedClient, Observable.empty(), Observable.empty(), Observable.empty(), apiClient
        )
        presenter = MarketOrderPresenter(status) { messages.add(it) }
    }

    @After
    fun resetSchedulers() {
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun orderEntryEnabledReflectsCredentialAvailabilityLoadingAndAmount() {
        val orderEntryEnabled = presenter.orderEntryEnabled.test()
        orderEntryEnabled.assertValue(false)

        presenter.amount.value = 0.5
        orderEntryEnabled.assertValues(false, true)

        status.switchAccount(null)
        orderEntryEnabled.assertValues(false, true, false)

        status.switchAccount(apiClient)
        orderEntryEnabled.assertValues(false, true, false, true)

        presenter.amount.value = 0.0001
        orderEntryEnabled.assertValues(false, true, false, true, false)
    }

    @Test
    fun privateClientAvailableTracksAccountSwitches() {
        val privateClientAvailable = presenter.privateClientAvailable.test()
        privateClientAvailable.assertValue(true)

        status.switchAccount(null)
        privateClientAvailable.assertValues(true, false)
    }

    @Test
    fun invalidQuantitiesDoNotSubmitMarketOrders() {
        listOf(0.0001, -0.001, Double.NaN, Double.POSITIVE_INFINITY).forEach { size ->
            presenter.amount.value = size
            presenter.buy()
            presenter.sell()
        }
        scheduler.triggerActions()

        Mockito.verify(apiClient, Mockito.never()).sendChildOrder(any())
        assertFalse(presenter.isLoading.value)
    }

    @Test
    fun acceptanceIdReportsMarketBuyAcceptance() {
        stubAcceptance("BUY")
        presenter.amount.value = 0.001

        presenter.buy()
        scheduler.triggerActions()

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    @Test
    fun acceptanceIdReportsMarketSellAcceptance() {
        stubAcceptance("SELL")
        presenter.amount.value = 0.001

        presenter.sell()
        scheduler.triggerActions()

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    @Test
    fun switchingAccountsDropsQueuedMarketOrdersAndAllowsNewOrders() {
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.5)
        val newAccount = Mockito.mock(APIClientV1::class.java)
        Mockito.`when`(newAccount.getPositions()).thenReturn(Observable.empty())
        Mockito.`when`(newAccount.getCollateral()).thenReturn(Observable.empty())
        Mockito.`when`(newAccount.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        Mockito.`when`(newAccount.sendChildOrder(request)).thenReturn(Observable.just(acceptedResponse))
        presenter.amount.value = 0.5

        presenter.buy()
        status.switchAccount(newAccount)
        scheduler.triggerActions()

        Mockito.verify(apiClient, Mockito.never()).sendChildOrder(request)
        Mockito.verify(newAccount, Mockito.never()).sendChildOrder(request)
        Mockito.verify(preferenceBackedClient, Mockito.never()).sendChildOrder(request)
        assertEquals(emptyList<Int>(), messages)
        assertEquals(false, presenter.isLoading.value)

        presenter.buy()
        scheduler.triggerActions()

        Mockito.verify(newAccount).sendChildOrder(request)
        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    @Test
    fun noActiveAccountLeavesMarketOrderUnsubmitted() {
        status.switchAccount(null)
        presenter.amount.value = 0.5

        presenter.buy()
        scheduler.triggerActions()

        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "BUY", null, 0.5)
        Mockito.verify(apiClient, Mockito.never()).sendChildOrder(request)
        Mockito.verify(preferenceBackedClient, Mockito.never()).sendChildOrder(request)
        assertTrue(messages.isEmpty())
        assertFalse(presenter.isLoading.value)
    }

    @Test
    fun invalidBuyAcceptancePromptsOrderCheckAndRestoresControls() {
        presenter.amount.value = 0.5

        assertSubmissionFailure(
            "BUY",
            error = JsonParseException("Invalid acceptance ID"),
            message = R.string.order_acceptance_uncertain,
            submit = presenter::buy
        )
    }

    @Test
    fun rejectedSellReportsFailureAndRestoresControls() {
        presenter.amount.value = 0.5

        assertSubmissionFailure("SELL", submit = presenter::sell)
    }

    @Test
    fun rejectedCloseReportsFailureAndRestoresControls() {
        status.position.value = listOf(openPosition("BUY", 0.5))

        Mockito.`when`(apiClient.getPositions("FX_BTC_JPY"))
            .thenReturn(Observable.just(status.position.value))

        assertSubmissionFailure("SELL", timeInForce = "IOC", submit = ::confirmPositionClose)
    }

    @Test
    fun uncertainMarketOrderPromptsOrderCheck() {
        presenter.amount.value = 0.5

        assertSubmissionFailure("BUY", error = orderHttpError(503), message = R.string.order_acceptance_uncertain,
            submit = presenter::buy)
    }

    @Test
    fun uncertainCloseOrderPromptsOrderCheck() {
        status.position.value = listOf(openPosition("BUY", 0.5))
        Mockito.`when`(apiClient.getPositions("FX_BTC_JPY"))
            .thenReturn(Observable.just(status.position.value))

        assertSubmissionFailure("SELL", timeInForce = "IOC", error = orderHttpError(503),
            message = R.string.order_acceptance_uncertain, submit = ::confirmPositionClose)
    }

    @Test
    fun rejectionAfterAnAcceptedCloseOrderReportsIncompleteClosure() {
        stubClose("SELL", "BUY", positionSize = 12.0)
        val nextOrder = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 2.0, timeInForce = "IOC")
        Mockito.`when`(apiClient.sendChildOrder(nextOrder)).thenReturn(
            Observable.error(orderHttpError(400))
        )

        confirmPositionClose()
        closePositionResponse.onNext(listOf(openPosition("BUY", 2.0)))

        assertEquals(listOf(R.string.market_order_accepted, R.string.close_positions_incomplete), messages)
        assertEquals(false, presenter.isLoading.value)
    }

    @Test
    fun closeReportsCompletionOnlyAfterFreshPositionsAreEmpty() {
        stubClose("SELL", "BUY")

        confirmPositionClose()
        confirmPositionClose()
        Mockito.verify(apiClient).sendChildOrder(
            SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5, timeInForce = "IOC")
        )
        status.position.value = emptyList()
        assertEquals(listOf(R.string.market_order_accepted), messages)
        assertEquals(true, presenter.isLoading.value)

        closePositionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
        assertEquals(false, presenter.isLoading.value)
    }

    @Test
    fun offsettingOpenPositionsReportIncompleteClosure() {
        stubClose("BUY", "SELL")

        confirmPositionClose()
        closePositionResponse.onNext(listOf(openPosition("BUY", 0.25), openPosition("SELL", 0.25)))

        assertEquals(listOf(R.string.market_order_accepted, R.string.close_positions_incomplete), messages)
    }

    @Test
    fun changedPositionRequiresReconfirmationBeforeSubmittingClose() {
        status.position.value = listOf(openPosition("BUY", 0.5))
        val confirmed = requireNotNull(presenter.positionCloseOrder())
        stubClose("BUY", "SELL")

        presenter.clearPosition(confirmed) { updated ->
            assertEquals("BUY", updated.order.side)
            assertEquals(emptyList<Int>(), messages)
            Mockito.verify(apiClient, Mockito.never()).sendChildOrder(
                SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5, timeInForce = "IOC")
            )
            presenter.clearPosition(updated) { error("Unexpected reconfirmation") }
        }
        scheduler.triggerActions()

        assertEquals(listOf(R.string.market_order_accepted), messages)
        closePositionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    @Test
    fun switchingAccountsStopsThePreviousCloseSequence() {
        stubClose("SELL", "BUY", positionSize = 12.0)
        val boardResponse = PublishSubject.create<Board>()
        Mockito.`when`(preferenceBackedClient.getBoard("FX_BTC_JPY")).thenReturn(boardResponse)
        confirmPositionClose()
        status.updateStatus()
        scheduler.triggerActions()
        assertTrue(boardResponse.hasObservers())
        assertTrue(closePositionResponse.hasObservers())

        val newAccount = Mockito.mock(APIClientV1::class.java)
        Mockito.`when`(newAccount.getPositions()).thenReturn(Observable.just(emptyList()))
        Mockito.`when`(newAccount.getCollateral()).thenReturn(Observable.empty())
        Mockito.`when`(newAccount.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        status.switchAccount(newAccount)
        scheduler.triggerActions()
        assertTrue(boardResponse.hasObservers())
        assertFalse(closePositionResponse.hasObservers())
        Mockito.verify(newAccount).getPositions()
        assertEquals(emptyList<Position>(), status.position.value)
        closePositionResponse.onNext(listOf(openPosition("BUY", 2.0)))
        Mockito.verify(apiClient, Mockito.never()).sendChildOrder(
            SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 2.0, timeInForce = "IOC")
        )
        assertEquals(false, presenter.isLoading.value)

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    private fun assertSubmissionFailure(
        side: String,
        timeInForce: String = "GTC",
        error: Throwable = orderHttpError(400),
        message: Int = R.string.market_order_failed,
        submit: () -> Unit
    ) {
        val response = PublishSubject.create<ChildOrderAcceptance>()
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.5, timeInForce = timeInForce)
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(response)
        val loading = presenter.isLoading.asObservable().test()

        submit()
        scheduler.triggerActions()
        loading.assertValues(false, true)
        assertEquals(emptyList<Int>(), messages)

        response.onError(error)

        loading.assertValues(false, true, false)
        assertEquals(listOf(message), messages)
    }

    private fun orderHttpError(statusCode: Int) =
        HttpException(Response.error<Any>(statusCode, "".toResponseBody()))

    private fun confirmPositionClose() {
        presenter.clearPosition(requireNotNull(presenter.positionCloseOrder())) {
            error("Unexpected reconfirmation")
        }
        scheduler.triggerActions()
    }

    private fun stubAcceptance(side: String) {
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.001)
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(Observable.just(acceptedResponse))
    }

    private fun stubClose(side: String, positionSide: String, positionSize: Double = 0.5) {
        val positions = listOf(openPosition(positionSide, positionSize))
        val firstOrderSize = minOf(positionSize, 10.0)
        status.position.value = positions
        Mockito.`when`(apiClient.getPositions("FX_BTC_JPY"))
            .thenReturn(Observable.just(positions), closePositionResponse.take(1), Observable.just(emptyList()))
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, firstOrderSize, timeInForce = "IOC")
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(Observable.just(acceptedResponse))
        val filledOrder = ChildOrder(
            1, "child", "FX_BTC_JPY", side, "MARKET", 0, 0, firstOrderSize,
            "COMPLETED", "JRF-accepted", 0.0, 0.0, firstOrderSize, 0.0
        )
        Mockito.`when`(apiClient.getChildOrders("FX_BTC_JPY", null, "JRF-accepted"))
            .thenReturn(Observable.just(listOf(filledOrder)))
    }

    private fun openPosition(side: String, size: Double) =
        Position("FX_BTC_JPY", side, 1000000L, size, 0.0, 0.0, 0.0, 1L, 0.0)
}
