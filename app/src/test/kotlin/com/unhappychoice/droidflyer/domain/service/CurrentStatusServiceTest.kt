package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.PrivateEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Execution
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import java.io.IOException
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CurrentStatusServiceTest {
    private val scheduler = TestScheduler()
    private val responses = PublishSubject.create<Board>()
    private val snapshots = PublishSubject.create<Board>()
    private val deltas = PublishSubject.create<Board>()
    private val executions = PublishSubject.create<List<Execution>>()
    private val disconnects = PublishSubject.create<Unit>()
    private val orderEvents = PublishSubject.create<PrivateEvent<*>>()
    private val initialBook = Board(100.0, emptyList(), listOf(Order(100, 1.0), Order(110, 1.0)))
    private val updatedBook = initialBook.copy(asks = listOf(Order(110, 1.0)))
    private val api = mock(APIClientV1::class.java)
    private lateinit var service: CurrentStatusService
    private var boardRequests = 0

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setComputationSchedulerHandler { scheduler }
        RxJavaPlugins.setSingleSchedulerHandler { scheduler }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { scheduler }

        `when`(api.getBoard("FX_BTC_JPY"))
            .thenReturn(responses.take(1).doOnSubscribe { boardRequests++ })
        `when`(api.getPositions()).thenReturn(Observable.empty())
        `when`(api.getCollateral()).thenReturn(Observable.empty())
        `when`(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        service = CurrentStatusService(
            api, executions, boardUpdates(snapshots, deltas), orderEvents,
            executionFeedDisconnects = disconnects
        )
        scheduler.triggerActions()
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.dispose()
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun stalledMainSchedulerDeliversOnlyTheLatestValidPriceInABurst() {
        val prices = service.currentPrice.asObservable().test()

        repeat(200) { publishPrice(100.0 + it) }
        publishPrice(Double.NaN)
        publishPrice(0.0)
        prices.assertValue(MarketPrice(null))

        scheduler.triggerActions()
        prices.assertValues(MarketPrice(null), MarketPrice(299.0))
    }

    @Test
    fun disconnectClearsTheDisplayedPriceWhenAPriceIsPending() {
        val totals = service.tradingTotals.test()
        publishPrice(100.0)
        scheduler.triggerActions()

        publishPrice(110.0)
        disconnects.onNext(Unit)
        scheduler.triggerActions()
        totals.assertValues(
            TradingTotals(null, null, null, null),
            TradingTotals(0L, 100L, null, 100L),
            TradingTotals(null, null, null, null)
        )

        publishPrice(120.0)
        scheduler.triggerActions()
        totals.assertValueAt(3, TradingTotals(0L, 120L, null, 120L)).assertValueCount(4)
    }

    @Test
    fun disconnectRemainsOrderedWhenTheFeedReconnectsBeforeMainDrains() {
        val prices = service.currentPrice.asObservable().test()

        publishPrice(100.0)
        publishPrice(110.0)
        disconnects.onNext(Unit)
        publishPrice(120.0)
        publishPrice(130.0)
        scheduler.triggerActions()

        prices.assertValues(MarketPrice(null), MarketPrice(110.0), MarketPrice(null), MarketPrice(130.0))
    }

    @Test
    fun accountUpdateUsesTheCoalescedPriceBeforeTheNextWindow() {
        val totals = service.tradingTotals.test()
        publishPrice(100.0)
        scheduler.triggerActions()

        publishPrice(110.0)
        publishPrice(120.0)
        scheduler.triggerActions()
        totals.assertValueCount(2)

        service.privateState.collateral.value = Collateral(1000.0)
        totals.assertValueAt(2, TradingTotals(0L, 120L, 1000L, 120L)).assertValueCount(3)
    }

    @Test
    fun delayedRefreshDoesNotRestoreAnAskDeletedByRealtimeBeforeTheFirstSnapshot() {
        responses.onNext(initialBook)
        scheduler.triggerActions()
        service.updateStatus()
        scheduler.triggerActions()

        deltas.onNext(initialBook.copy(asks = listOf(Order(100, 0.0))))
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(updatedBook, 110)

        responses.onNext(initialBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(updatedBook, 110)
    }

    @Test
    fun delayedRefreshDoesNotReplaceARealtimeSnapshot() {
        snapshots.onNext(updatedBook)
        scheduler.triggerActions()

        responses.onNext(initialBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(updatedBook, 110)
    }

    @Test
    fun refreshPopulatesBookWhenNoRealtimeUpdatesArrive() {
        responses.onNext(initialBook)
        scheduler.triggerActions()
        assertEquals(100L, service.buyPrice.value)
        assertEquals(emptyList<Order>(), service.board.value.asks)

        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(initialBook, 100)
    }

    @Test
    fun fundingRefreshesWhileVisibleAndRetainsTheLastQuoteAfterFailure() {
        val settlement = "2024-04-15T13:00:00"
        val zero = FundingRate(BigDecimal.ZERO, settlement)
        val negative = FundingRate(BigDecimal("-0.00375"), settlement)
        val failure = Observable.error<FundingRate>(IOException("Offline"))
        `when`(api.getFundingRate("FX_BTC_JPY")).thenReturn(
            failure, Observable.just(zero), Observable.just(zero.copy(ratio = null)), Observable.just(negative)
        )
        val visible = PublishSubject.create<Boolean>()
        val funding = service.observeFunding(visible).test()
        val zeroQuote = NextFunding(BigDecimal.ZERO, 1713186000000L)
        val negativeQuote = zeroQuote.copy(ratio = BigDecimal("-0.00375"))

        visible.onNext(true)
        scheduler.triggerActions()
        funding.assertValues(FundingStatus())

        scheduler.advanceTimeBy(59, TimeUnit.SECONDS)
        verify(api).getFundingRate("FX_BTC_JPY")
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        funding.assertValues(FundingStatus(), FundingStatus(zeroQuote))

        scheduler.advanceTimeBy(1, TimeUnit.MINUTES)
        visible.onNext(false)
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES)
        verify(api, times(3)).getFundingRate("FX_BTC_JPY")
        funding.assertValues(FundingStatus(), FundingStatus(zeroQuote), FundingStatus(zeroQuote, stale = true))
        service.observeFunding(Observable.just(false)).test()
            .assertValue(FundingStatus(zeroQuote, stale = true))
            .dispose()

        visible.onNext(true)
        scheduler.triggerActions()
        funding.assertValues(
            FundingStatus(), FundingStatus(zeroQuote), FundingStatus(zeroQuote, stale = true), FundingStatus(negativeQuote)
        )
        funding.dispose()
    }

    @Test
    fun completedRefreshesReleaseBoardRequestsAndKeepRealtimeSubscribed() {
        repeat(10) {
            assertTrue(responses.hasObservers())
            responses.onNext(initialBook)
            scheduler.triggerActions()
            assertFalse(responses.hasObservers())
            assertTrue(snapshots.hasObservers())

            service.updateStatus()
            scheduler.triggerActions()
        }

        assertTrue(responses.hasObservers())
        service.dispose()
        assertFalse(responses.hasObservers())
        assertFalse(snapshots.hasObservers())
    }

    @Test
    fun orderEventQueuesAccountRefreshWhileBoardResponseIsHeld() {
        val orderResponses = PublishSubject.create<List<ChildOrder>>()
        `when`(api.getChildOrders("FX_BTC_JPY", count = 500))
            .thenReturn(orderResponses.take(1))
        snapshots.onNext(updatedBook)
        service.updateStatus()
        scheduler.triggerActions()

        orderEvents.onNext(PrivateEvent(Unit) { true })
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        verify(api, times(2)).getChildOrders("FX_BTC_JPY", count = 500)

        orderResponses.onNext(emptyList())
        scheduler.triggerActions()
        verify(api, times(3)).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(responses.hasObservers())
        assertEquals(1, boardRequests)
    }

    @Test
    fun refreshesAndAccountSwitchShareAnInFlightFallback() {
        service.pollStatus()
        service.updateStatus()
        service.switchAccount(null)
        scheduler.triggerActions()
        assertEquals(1, boardRequests)
        assertTrue(responses.hasObservers())

        responses.onNext(updatedBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertFalse(responses.hasObservers())
        assertEquals(1, boardRequests)
        assertBook(updatedBook, 110)
    }

    @Test
    fun realtimeSnapshotStopsNewFallbackRequests() {
        snapshots.onNext(updatedBook)
        responses.onNext(initialBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)

        service.pollStatus()
        service.updateStatus()
        scheduler.advanceTimeBy(300, MILLISECONDS)
        verify(api).getBoard("FX_BTC_JPY")
        verify(api, times(3)).getChildOrders("FX_BTC_JPY", count = 500)
        assertBook(updatedBook, 110)
    }

    private fun publishPrice(price: Double) {
        executions.onNext(listOf(Execution(0L, "BUY", price, 1.0, Date(0L), "", "")))
    }

    private fun assertBook(expected: Board, bestAsk: Long) {
        assertEquals(expected, service.board.value)
        assertEquals(bestAsk, service.buyPrice.value)
    }
}
