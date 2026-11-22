package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.FundingRate
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
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
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { toV3Scheduler(Schedulers.trampoline()) }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(scheduler) }

        `when`(api.getBoard("FX_BTC_JPY"))
            .thenReturn(responses.take(1).doOnSubscribe { boardRequests++ })
        `when`(api.getPositions()).thenReturn(Observable.empty())
        `when`(api.getCollateral()).thenReturn(Observable.empty())
        `when`(api.getChildOrders("FX_BTC_JPY", count = 500)).thenReturn(Observable.empty())
        service = CurrentStatusService(api, Observable.empty(), boardUpdates(snapshots, deltas), Observable.empty())
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
    fun manualRefreshQueuesOneBoardRequestWhilePollingSkipsAnActiveRefresh() {
        service.pollStatus()
        service.updateStatus()
        service.updateStatus()
        scheduler.triggerActions()
        assertEquals(1, boardRequests)

        responses.onNext(initialBook)
        scheduler.triggerActions()
        assertEquals(2, boardRequests)

        service.pollStatus()
        responses.onNext(updatedBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertEquals(2, boardRequests)
        assertBook(updatedBook, 110)
    }

    @Test
    fun switchingToNoPrivateAccountReplacesTheInFlightBoardRequest() {
        assertEquals(1, boardRequests)

        service.switchAccount(null)
        assertFalse(responses.hasObservers())
        scheduler.triggerActions()
        assertEquals(2, boardRequests)

        responses.onNext(updatedBook)
        scheduler.advanceTimeBy(300, MILLISECONDS)
        assertFalse(responses.hasObservers())
        assertEquals(2, boardRequests)
        assertBook(updatedBook, 110)
    }

    private fun assertBook(expected: Board, bestAsk: Long) {
        assertEquals(expected, service.board.value)
        assertEquals(bestAsk, service.buyPrice.value)
    }
}
