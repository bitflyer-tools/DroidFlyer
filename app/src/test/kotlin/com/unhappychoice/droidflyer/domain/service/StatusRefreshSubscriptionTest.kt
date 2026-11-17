package com.unhappychoice.droidflyer.domain.service

import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusRefreshSubscriptionTest {
    @Test
    fun completedAndFailedRefreshesLeaveOnlyRealtimeSubscribed() {
        val bag = CompositeDisposable()
        val realtime = PublishSubject.create<Int>()
        bag.add(realtime.subscribe())
        val values = mutableListOf<Int>()

        repeat(10) { value ->
            Observable.just(value).subscribeRefresh(bag) { values.add(it) }
            assertEquals(1, bag.size())

            val request = PublishSubject.create<Int>()
            request.subscribeRefresh(bag) { values.add(it) }
            assertEquals(2, bag.size())
            request.onError(IllegalStateException("Request failed"))
            assertEquals(1, bag.size())
        }

        assertEquals((0 until 10).toList(), values)
        assertTrue(realtime.hasObservers())
        bag.dispose()
    }

    @Test
    fun ownerCanCancelActiveRefreshes() {
        val bag = CompositeDisposable()
        val request = PublishSubject.create<Int>()
        val values = mutableListOf<Int>()
        request.subscribeRefresh(bag) { values.add(it) }

        assertTrue(request.hasObservers())
        assertEquals(1, bag.size())
        bag.dispose()
        request.onNext(1)
        assertFalse(request.hasObservers())
        assertTrue(values.isEmpty())
        assertEquals(0, bag.size())
    }
}
