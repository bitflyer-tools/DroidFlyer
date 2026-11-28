package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.PrivateEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Position
import com.unhappychoice.droidflyer.presentation.presenter.OrdersPresenter
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.schedulers.TestScheduler
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.IOException
import java.math.BigDecimal
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
    private val disconnects = PublishSubject.create<Unit>()
    private val orderEvents = PublishSubject.create<PrivateEvent<List<ChildOrderEvent>>>()
    private val initialBook = Board(100.0, emptyList(), listOf(Order(100, 1.0), Order(110, 1.0)))
    private val updatedBook = initialBook.copy(asks = listOf(Order(110, 1.0)))
    private val activeOrder = ChildOrder(
        1, "order", "FX_BTC_JPY", "SELL", "LIMIT", 110, 0, 0.5, "ACTIVE",
        "acceptance", 0.5, 0.0, 0.0, 0.0
    )
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
            api, Observable.empty(), boardUpdates(snapshots, deltas), orderEvents,
            publicFeedDisconnects = disconnects
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
    fun deltaBeforeAnyBaselineLeavesQuotesUnavailableAndDoesNotInvalidateTheFallback() {
        deltas.onNext(Board(0.0, emptyList(), listOf(Order(120, 1.0))))
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(Board(0.0, emptyList(), emptyList()), 0)
        assertEquals(0L, service.sellPrice.value)

        responses.onNext(initialBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(initialBook, 100)

        deltas.onNext(initialBook.copy(asks = listOf(Order(100, 0.0))))
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
    fun disconnectRejectsEarlierFallbackAndWaitsForASnapshotBeforeApplyingDeltas() {
        snapshots.onNext(initialBook)
        disconnects.onNext(Unit)
        responses.onNext(initialBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(Board(0.0, emptyList(), emptyList()), 0)

        service.pollStatus()
        scheduler.triggerActions()
        deltas.onNext(initialBook.copy(asks = listOf(Order(90, 1.0))))
        scheduler.triggerActions()
        assertEquals(0L, service.buyPrice.value)

        responses.onNext(updatedBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertBook(updatedBook, 110)
        assertEquals(2, boardRequests)
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

        orderEvents.onNext(PrivateEvent(emptyList()) { true })
        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)
        verify(api, times(2)).getChildOrders("FX_BTC_JPY", count = 500)

        orderResponses.onNext(emptyList())
        scheduler.triggerActions()
        verify(api, times(3)).getChildOrders("FX_BTC_JPY", count = 500)
        assertTrue(responses.hasObservers())
        assertEquals(1, boardRequests)
    }

    @Test
    fun cancellationQueuesOneOrderReadWhilePositionsAndCollateralAreHeld() {
        val positions = PublishSubject.create<List<Position>>()
        val collateral = PublishSubject.create<Map<String, Any>>()
        val orderResponses = PublishSubject.create<List<ChildOrder>>()
        `when`(api.getPositions()).thenReturn(positions.take(1))
        `when`(api.getCollateral()).thenReturn(collateral.take(1))
        `when`(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(orderResponses.take(1))
        `when`(api.cancelChildOrder(CancelChildOrderRequest(activeOrder.productCode, activeOrder.childOrderAcceptanceId)))
            .thenReturn(Completable.complete())
        val presenter = OrdersPresenter(service)
        val rows = presenter.orders.test()

        service.updateStatus()
        scheduler.triggerActions()
        orderResponses.onNext(listOf(activeOrder))
        scheduler.triggerActions()
        val row = rows.values().last().single()

        service.pollStatus()
        scheduler.triggerActions()
        repeat(2) { presenter.cancelOrder(row) }
        scheduler.triggerActions()
        verify(api, times(3)).getChildOrders("FX_BTC_JPY", count = 500)

        orderResponses.onNext(listOf(activeOrder))
        scheduler.triggerActions()
        verify(api, times(4)).getChildOrders("FX_BTC_JPY", count = 500)
        orderResponses.onNext(emptyList())
        scheduler.triggerActions()

        rows.assertValues(emptyList(), listOf(row), emptyList())
        assertTrue(positions.hasObservers())
        assertTrue(collateral.hasObservers())
        verify(api, times(2)).getPositions()
        verify(api, times(2)).getCollateral()
        verify(api, times(4)).getChildOrders("FX_BTC_JPY", count = 500)
    }

    @Test
    fun accountSwitchDropsAnOrderResponseQueuedForDelivery() {
        val oldOrders = PublishSubject.create<List<ChildOrder>>()
        val newOrder = activeOrder.copy(childOrderAcceptanceId = "new-acceptance")
        val newAccount = mock(APIClientV1::class.java)
        `when`(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(oldOrders.take(1))
        `when`(newAccount.getPositions()).thenReturn(Observable.empty())
        `when`(newAccount.getCollateral()).thenReturn(Observable.empty())
        `when`(newAccount.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.just(listOf(newOrder)))
        val orders = service.orders.asObservable().test()

        service.updateActiveOrders()
        scheduler.triggerActions()
        oldOrders.onNext(listOf(activeOrder))
        service.switchAccount(newAccount)
        scheduler.triggerActions()

        orders.assertValueAt(orders.values().lastIndex, listOf(newOrder))
        assertFalse(listOf(activeOrder) in orders.values())
        assertFalse(oldOrders.hasObservers())
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

    private fun assertBook(expected: Board, bestAsk: Long) {
        assertEquals(expected, service.board.value)
        assertEquals(bestAsk, service.buyPrice.value)
    }
}
