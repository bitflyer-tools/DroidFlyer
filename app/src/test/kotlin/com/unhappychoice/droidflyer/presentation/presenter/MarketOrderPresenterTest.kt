package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MarketOrderPresenterTest {
    private val apiClient = Mockito.mock(APIClientV1::class.java)
    private val closePositionResponse = PublishSubject.create<List<Position>>()
    private val acceptedResponse = mapOf<String, Any>("child_order_acceptance_id" to "JRF-accepted")
    private val messages = mutableListOf<Int>()
    private lateinit var status: CurrentStatusService
    private lateinit var presenter: MarketOrderPresenter

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }

        Mockito.`when`(apiClient.getCollateral()).thenReturn(Observable.empty())
        Mockito.`when`(apiClient.getBoard("FX_BTC_JPY")).thenReturn(Observable.empty())
        Mockito.`when`(apiClient.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())
        Mockito.`when`(apiClient.getPositions()).thenReturn(Observable.empty())
        status = CurrentStatusService(apiClient, Observable.empty(), Observable.empty(), Observable.empty())
        val closeService = ClosePositionService(apiClient, TestScheduler())
        presenter = MarketOrderPresenter(apiClient, status, closeService) { messages.add(it) }
    }

    @After
    fun resetSchedulers() {
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun acceptanceIdReportsMarketBuyAcceptance() {
        stubAcceptance("BUY")
        presenter.amount.value = 0.5

        presenter.buy()

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    @Test
    fun acceptanceIdReportsMarketSellAcceptance() {
        stubAcceptance("SELL")
        presenter.amount.value = 0.5

        presenter.sell()

        assertEquals(listOf(R.string.market_order_accepted), messages)
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
            assertEquals("BUY", updated.side)
            assertEquals(emptyList<Int>(), messages)
            Mockito.verify(apiClient, Mockito.never()).sendChildOrder(
                SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5, timeInForce = "IOC")
            )
            presenter.clearPosition(updated) { error("Unexpected reconfirmation") }
        }

        assertEquals(listOf(R.string.market_order_accepted), messages)
        closePositionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    private fun confirmPositionClose() {
        presenter.clearPosition(requireNotNull(presenter.positionCloseOrder())) {
            error("Unexpected reconfirmation")
        }
    }

    private fun stubAcceptance(side: String) {
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.5)
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(Observable.just(acceptedResponse))
    }

    private fun stubClose(side: String, positionSide: String) {
        val positions = listOf(openPosition(positionSide, 0.5))
        status.position.value = positions
        Mockito.`when`(apiClient.getPositions("FX_BTC_JPY"))
            .thenReturn(Observable.just(positions), closePositionResponse.take(1), Observable.just(emptyList()))
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.5, timeInForce = "IOC")
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(Observable.just(acceptedResponse))
        val filledOrder = ChildOrder(
            1, "child", "FX_BTC_JPY", side, "MARKET", 0, 0, 0.5,
            "COMPLETED", "JRF-accepted", 0.0, 0.0, 0.5, 0.0
        )
        Mockito.`when`(apiClient.getChildOrders("FX_BTC_JPY", null, "JRF-accepted"))
            .thenReturn(Observable.just(listOf(filledOrder)))
    }

    private fun openPosition(side: String, size: Double) =
        Position("FX_BTC_JPY", side, 1000000L, size, 0.0, 0.0, 0.0, 1L, 0.0)
}
