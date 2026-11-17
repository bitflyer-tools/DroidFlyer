package com.unhappychoice.droidflyer.domain.service

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.CompletableSubject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class StatusRefreshCoordinatorTest {
    @Test
    fun slowPreCancelResponseCannotOverwritePostCancelOrders() {
        val scheduler = TestScheduler()
        val responses = listOf(
            Observable.just(listOf("order")).delay(10, SECONDS, scheduler),
            Observable.just(emptyList<String>()).delay(1, SECONDS, scheduler)
        )
        val publishedOrders = mutableListOf<List<String>>()
        var requests = 0
        val coordinator = StatusRefreshCoordinator {
            responses[requests++].doOnNext { publishedOrders.add(it) }.ignoreElements()
        }

        coordinator.request(followUp = false)
        coordinator.request(followUp = true)
        coordinator.request(followUp = true)
        scheduler.advanceTimeBy(9, SECONDS)

        assertEquals(1, requests)
        assertEquals(emptyList<List<String>>(), publishedOrders)

        scheduler.advanceTimeBy(1, SECONDS)
        assertEquals(2, requests)
        assertEquals(listOf(listOf("order")), publishedOrders)

        scheduler.advanceTimeBy(1, SECONDS)
        assertEquals(listOf(listOf("order"), emptyList<String>()), publishedOrders)
        assertEquals(2, requests)
    }

    @Test
    fun periodicTicksDuringARefreshDoNotQueueAnotherRefresh() {
        val response = CompletableSubject.create()
        var requests = 0
        val coordinator = StatusRefreshCoordinator {
            requests++
            response
        }

        coordinator.request(followUp = false)
        coordinator.request(followUp = false)
        response.onComplete()

        assertEquals(1, requests)
        coordinator.request(followUp = false)
        assertEquals(2, requests)
    }

    @Test
    fun failedRefreshStillRunsThePendingFollowUp() {
        val response = CompletableSubject.create()
        var requests = 0
        val coordinator = StatusRefreshCoordinator {
            if (requests++ == 0) response else Completable.complete()
        }

        coordinator.request(followUp = false)
        coordinator.request(followUp = true)
        response.onError(IllegalStateException("Request failed"))

        assertEquals(2, requests)
    }
}
