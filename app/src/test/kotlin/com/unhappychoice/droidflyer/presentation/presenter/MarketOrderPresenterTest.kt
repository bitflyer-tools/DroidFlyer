package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MarketOrderPresenterTest {
    private val apiClient = Mockito.mock(APIClientV1::class.java)
    private val positionResponse = PublishSubject.create<List<Position>>()
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
        Mockito.`when`(apiClient.getPositions()).thenReturn(Observable.empty(), positionResponse.take(1))
        status = CurrentStatusService(apiClient, Observable.empty(), Observable.empty(), Observable.empty())
        presenter = MarketOrderPresenter(apiClient, status) { messages.add(it) }
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
    fun closeReportsAcceptanceUntilConfirmedPositionsAreEmpty() {
        stubAcceptance("SELL")
        status.position.value = listOf(openPosition("BUY", 0.5))

        confirmPositionClose()
        assertEquals(listOf(R.string.market_order_accepted), messages)

        positionResponse.onNext(listOf(openPosition("BUY", 0.25)))
        assertEquals(listOf(R.string.market_order_accepted), messages)

        status.updateStatus()
        positionResponse.onNext(emptyList())
        status.updateStatus()
        positionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    @Test
    fun offsettingOpenPositionsDoNotReportClosure() {
        stubAcceptance("BUY")
        status.position.value = listOf(openPosition("SELL", 0.5))

        confirmPositionClose()
        positionResponse.onNext(listOf(openPosition("BUY", 0.25), openPosition("SELL", 0.25)))

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    @Test
    fun closeIgnoresCachedEmptyPositionsAndRequestsStartedBeforeAcceptance() {
        val acceptance = PublishSubject.create<Map<String, Any>>()
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5)
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(acceptance)
        status.position.value = listOf(openPosition("BUY", 0.5))
        val previousResponse = PublishSubject.create<List<Position>>()
        Mockito.`when`(apiClient.getPositions())
            .thenReturn(Observable.just(emptyList()), previousResponse.take(1), positionResponse.take(1))

        confirmPositionClose()
        status.updateStatus()
        status.updateStatus()
        assertEquals(emptyList<Position>(), status.position.value)

        acceptance.onNext(acceptedResponse)
        assertEquals(listOf(R.string.market_order_accepted), messages)

        previousResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted), messages)

        positionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    @Test
    fun changedPositionRequiresReconfirmationBeforeReportingAcceptance() {
        stubAcceptance("BUY")
        status.position.value = listOf(openPosition("BUY", 0.5))
        val confirmed = requireNotNull(presenter.positionCloseOrder())
        status.position.value = listOf(openPosition("SELL", 0.5))

        presenter.clearPosition(confirmed) { updated ->
            assertEquals("BUY", updated.order.side)
            assertEquals(emptyList<Int>(), messages)
            Mockito.verify(apiClient, Mockito.never()).sendChildOrder(
                SendChildOrderRequest("FX_BTC_JPY", "MARKET", "SELL", null, 0.5)
            )
            presenter.clearPosition(updated) { error("Unexpected reconfirmation") }
        }

        assertEquals(listOf(R.string.market_order_accepted), messages)
        positionResponse.onNext(emptyList())
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    @Test
    fun newAccountPositionsDoNotConfirmPreviousAccountClosure() {
        stubAcceptance("SELL")
        status.position.value = listOf(openPosition("BUY", 0.5))
        confirmPositionClose()

        val newAccount = Mockito.mock(APIClientV1::class.java)
        Mockito.`when`(newAccount.getPositions()).thenReturn(Observable.just(emptyList()))
        Mockito.`when`(newAccount.getCollateral()).thenReturn(Observable.empty())
        Mockito.`when`(newAccount.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())
        status.privateState.switchAccount(newAccount)
        positionResponse.onNext(emptyList())

        assertEquals(listOf(R.string.market_order_accepted), messages)
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

    private fun openPosition(side: String, size: Double) =
        Position("FX_BTC_JPY", side, 1000000L, size, 0.0, 0.0, 0.0, 1L, 0.0)
}
