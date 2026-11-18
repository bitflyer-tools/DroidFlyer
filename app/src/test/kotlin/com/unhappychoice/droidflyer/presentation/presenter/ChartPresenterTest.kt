package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ExecutionPrice
import io.reactivex.Observable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ChartPresenterTest {
    @Test
    fun loadsRecentCfdPricesInExecutionOrder() {
        val presenter = ChartPresenter { productCode, count ->
            assertEquals("FX_BTC_JPY", productCode)
            assertEquals(500, count)
            Observable.just(listOf(ExecutionPrice(3, 300.0), ExecutionPrice(1, 100.0), ExecutionPrice(2, 250.0)))
        }

        presenter.loadPrices().test().assertResult(listOf(100.0, 250.0, 300.0))
    }

    @Test
    fun retryMakesAFreshRequestAfterFailure() {
        var attempts = 0
        val presenter = ChartPresenter { _, _ ->
            attempts++
            if (attempts == 1) Observable.error(IOException("Offline"))
            else Observable.just(listOf(ExecutionPrice(1, 100.0)))
        }

        presenter.loadPrices().test().assertFailure(IOException::class.java)
        presenter.loadPrices().test().assertResult(listOf(100.0))
        assertEquals(2, attempts)
    }

    @Test
    fun rejectsEmptyExecutionHistory() {
        val presenter = ChartPresenter { _, _ -> Observable.just(emptyList()) }

        presenter.loadPrices().test().assertFailure(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsInvalidPrices() {
        val presenter = ChartPresenter { _, _ -> Observable.just(listOf(ExecutionPrice(1, 0.0))) }

        presenter.loadPrices().test().assertFailure(IllegalArgumentException::class.java)
    }
}
