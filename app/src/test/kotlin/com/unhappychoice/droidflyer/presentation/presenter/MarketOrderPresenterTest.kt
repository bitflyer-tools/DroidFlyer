package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class MarketOrderPresenterTest {
    private val apiClient = Mockito.mock(APIClientV1::class.java)
    private val position = Variable<List<Position>>(emptyList())
    private val messages = mutableListOf<Int>()
    private val presenter = MarketOrderPresenter(apiClient, position, {}, { messages.add(it) })

    @Before
    fun useSynchronousSchedulers() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
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
        position.value = listOf(openPosition("BUY", 0.5))

        presenter.clearPosition()
        assertEquals(listOf(R.string.market_order_accepted), messages)

        position.value = listOf(openPosition("BUY", 0.25))
        assertEquals(listOf(R.string.market_order_accepted), messages)

        position.value = emptyList()
        position.value = emptyList()
        assertEquals(listOf(R.string.market_order_accepted, R.string.cleared_all_positions), messages)
    }

    @Test
    fun offsettingOpenPositionsDoNotReportClosure() {
        stubAcceptance("BUY")
        position.value = listOf(openPosition("SELL", 0.5))

        presenter.clearPosition()
        position.value = listOf(openPosition("BUY", 0.25), openPosition("SELL", 0.25))

        assertEquals(listOf(R.string.market_order_accepted), messages)
    }

    private fun stubAcceptance(side: String) {
        val request = SendChildOrderRequest("FX_BTC_JPY", "MARKET", side, null, 0.5)
        val response = mapOf<String, Any>("child_order_acceptance_id" to "JRF-accepted")
        Mockito.`when`(apiClient.sendChildOrder(request)).thenReturn(Observable.just(response))
    }

    private fun openPosition(side: String, size: Double) =
        Position("FX_BTC_JPY", side, 1000000L, size, 0.0, 0.0, 0.0, 1L, 0.0)
}
