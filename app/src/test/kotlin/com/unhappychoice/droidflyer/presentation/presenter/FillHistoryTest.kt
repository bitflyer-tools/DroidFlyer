package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import io.reactivex.Single
import io.reactivex.subjects.SingleSubject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class FillHistoryTest {
    @Test
    fun pagesByOldestIdAndRefreshReplacesHistory() {
        val cursors = mutableListOf<Long?>()
        val history = FillHistory { count, before ->
            cursors.add(before)
            val ids = when (cursors.size) {
                1 -> (2L..(count + 1L)).toList()
                2 -> listOf(2L, 1L)
                else -> listOf(52L)
            }
            Single.just(ids.map(::fill))
        }

        history.refresh()
        assertEquals((51L downTo 2L).toList(), history.state.value.fills.map { it.id })
        history.loadOlder()
        assertEquals((51L downTo 1L).toList(), history.state.value.fills.map { it.id })
        assertNull(history.state.value.nextCursor)
        history.loadOlder()
        history.refresh()
        assertEquals(listOf(null, 2L, null), cursors)
        assertEquals(listOf(fill(52L)), history.state.value.fills)
    }

    @Test
    fun distinguishesEmptyHistoryFromAccessDenied() {
        var result: Single<List<PrivateExecution>> = Single.just(emptyList())
        val history = FillHistory { _, _ -> result }

        history.refresh()
        assertEquals(FillHistory.State(loaded = true), history.state.value)

        listOf(401, 403).forEach { status ->
            result = Single.error(HttpException(Response.error<Any>(status, "".toResponseBody())))
            history.refresh()
            assertEquals(FillHistory.State(error = FillHistory.Error.ACCESS_DENIED), history.state.value)
        }
    }

    @Test
    fun failedOlderPageKeepsRowsAndCursorForRetry() {
        val history = FillHistory { count, before ->
            if (before == null) Single.just((1L..count.toLong()).map(::fill))
            else Single.error(IOException("Offline"))
        }

        history.refresh()
        val loaded = history.state.value
        history.loadOlder()
        assertEquals(loaded.copy(error = FillHistory.Error.REQUEST_FAILED), history.state.value)
    }

    @Test
    fun avoidsOverlappingLoadsAndCancelsOnClear() {
        val pending = SingleSubject.create<List<PrivateExecution>>()
        var requests = 0
        val history = FillHistory { _, _ ->
            requests++
            pending
        }

        history.refresh()
        history.refresh()
        assertTrue(history.state.value.loading)
        assertEquals(1, requests)
        history.clear()
        assertFalse(pending.hasObservers())
        pending.onSuccess(listOf(fill(1L)))
        assertEquals(FillHistory.State(), history.state.value)
    }

    private fun fill(id: Long) = PrivateExecution(
        id, "BUY", "33470".toBigDecimal(), "0.01".toBigDecimal(), "2015-07-07T09:57:40.397"
    )
}
