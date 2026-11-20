package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ChartPresenterTest {
    @Test
    fun loadsRecentCfdPricesInExecutionOrder() {
        val presenter = ChartPresenter({ 0L }) { productCode, count ->
            assertEquals("FX_BTC_JPY", productCode)
            assertEquals(500, count)
            Observable.just(listOf(ExecutionPrice(3, 300.0), ExecutionPrice(1, 100.0), ExecutionPrice(2, 250.0)))
        }

        presenter.loadPrices().test().assertResult(listOf(100.0, 250.0, 300.0))
    }

    @Test
    fun retryMakesAFreshRequestAfterFailure() {
        var attempts = 0
        val presenter = ChartPresenter({ 0L }) { _, _ ->
            attempts++
            if (attempts == 1) Observable.error(IOException("Offline"))
            else Observable.just(listOf(ExecutionPrice(1, 100.0)))
        }

        presenter.loadPrices().test().assertFailure(IOException::class.java)
        assertNull(presenter.cachedPrices())
        presenter.loadPrices().test().assertResult(listOf(100.0))
        assertEquals(2, attempts)
    }

    @Test
    fun cachedPricesExpireThirtySecondsAfterTheSuccessfulResponse() {
        var nowNanos = 0L
        val executions = PublishSubject.create<List<ExecutionPrice>>()
        val presenter = ChartPresenter(nowNanos = { nowNanos }) { _, _ -> executions }
        val load = presenter.loadPrices().test()

        nowNanos = TimeUnit.SECONDS.toNanos(40)
        executions.onNext(listOf(ExecutionPrice(1, 100.0)))
        executions.onComplete()
        load.assertResult(listOf(100.0))
        nowNanos = TimeUnit.SECONDS.toNanos(70) - 1
        assertEquals(listOf(100.0), presenter.cachedPrices())
        nowNanos++
        assertNull(presenter.cachedPrices())
    }

    @Test
    fun rejectsEmptyExecutionHistory() {
        val presenter = ChartPresenter({ 0L }) { _, _ -> Observable.just(emptyList()) }

        presenter.loadPrices().test().assertFailure(IllegalArgumentException::class.java)
        assertNull(presenter.cachedPrices())
    }

    @Test
    fun rejectsInvalidPrices() {
        val presenter = ChartPresenter({ 0L }) { _, _ -> Observable.just(listOf(ExecutionPrice(1, 0.0))) }

        presenter.loadPrices().test().assertFailure(IllegalArgumentException::class.java)
    }
}
