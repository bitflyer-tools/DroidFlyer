package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Board
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.Order
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.Observable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.TimeUnit.MILLISECONDS

class CurrentStatusServiceTest {
    private val scheduler = TestScheduler()
    private val responses = PublishSubject.create<Board>()
    private val snapshots = PublishSubject.create<Board>()
    private val deltas = PublishSubject.create<Board>()
    private val initialBook = Board(100.0, emptyList(), listOf(Order(100, 1.0), Order(110, 1.0)))
    private val updatedBook = initialBook.copy(asks = listOf(Order(110, 1.0)))
    private lateinit var service: CurrentStatusService
    private var boardRequests = 0

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
        RxJavaPlugins.setSingleSchedulerHandler { scheduler }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { toV3Scheduler(Schedulers.trampoline()) }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(scheduler) }

        val api = mock(APIClientV1::class.java)
        `when`(api.getBoard("FX_BTC_JPY"))
            .thenReturn(responses.take(1).doOnSubscribe { boardRequests++ })
        `when`(api.getPositions()).thenReturn(Observable.empty())
        `when`(api.getCollateral()).thenReturn(Observable.empty())
        `when`(api.getChildOrders("FX_BTC_JPY")).thenReturn(Observable.empty())
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

    private fun assertBook(expected: Board, bestAsk: Long) {
        assertEquals(expected, service.board.value)
        assertEquals(bestAsk, service.buyPrice.value)
    }
}
