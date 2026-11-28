package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.round
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.OrderAcceptanceUncertainException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.submitChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sign

internal class ClosePositionService(
    private val apiClient: APIClientV1,
    private val scheduler: Scheduler = Schedulers.io()
) {
    fun close(confirmedSize: Double, onAccepted: () -> Unit = {}): Single<Unit> =
        positionSize().flatMap { size ->
            check(size == 0.0 || size == confirmedSize) { "Position changed since confirmation" }
            closeRemaining(size, confirmedSize, onAccepted)
        }

    private fun closeRemaining(
        size: Double,
        confirmedSize: Double,
        onFirstAccepted: (() -> Unit)? = null
    ): Single<Unit> {
        if (size == 0.0) return Single.just(Unit)
        if (size.sign != confirmedSize.sign || abs(size) > abs(confirmedSize)) {
            return Single.error(IllegalStateException("Position changed since confirmation"))
        }

        val quantity = abs(size)
        if (!CryptoCfdOrderSize.meetsMinimum(quantity)) {
            return Single.error(IllegalStateException("Position is below the minimum close order size"))
        }

        val side = if (size > 0.0) "SELL" else "BUY"
        val request = SendChildOrderRequest(
            PRODUCT_CODE, "MARKET", side, null, nextOrderSize(quantity), timeInForce = "IOC"
        )
        return apiClient.submitChildOrder(request).singleOrError()
            .onErrorResumeNext { error ->
                Single.error(
                    if (onFirstAccepted != null && error !is OrderAcceptanceUncertainException) {
                        InitialOrderSubmissionException(error)
                    } else error
                )
            }
            .flatMap { acceptance ->
                onFirstAccepted?.invoke()
                executedSize(acceptance.acceptanceId)
            }
            .flatMap { filled ->
                check(filled > 0.0 && filled <= request.size) { "Invalid close order fill size" }
                val expected = (abs(size) - filled).round(8)
                poll({ positionSize().toObservable() }) {
                    abs(it) <= expected || it.sign != size.sign
                }.flatMap { remaining -> closeRemaining(remaining, size) }
            }
    }

    private fun nextOrderSize(quantity: Double): Double {
        if (quantity <= ORDER_SIZE_LIMIT) return quantity
        val minimum = CryptoCfdOrderSize.MINIMUM
        val remainder = (quantity - ORDER_SIZE_LIMIT).round(8)
        return if (remainder < minimum) (quantity - minimum).round(8) else ORDER_SIZE_LIMIT
    }

    private fun executedSize(acceptanceId: String): Single<Double> =
        poll({ apiClient.getChildOrders(PRODUCT_CODE, null, acceptanceId).flatMapIterable { it } }) {
            it.childOrderAcceptanceId == acceptanceId &&
                it.childOrderState in finalOrderStates && it.outstandingSize == 0.0
        }.map { it.executedSize }

    private fun positionSize(): Single<Double> =
        apiClient.getPositions(PRODUCT_CODE).singleOrError().map { positions ->
            val size = positions.wholeSize()
            check(size != 0.0 || positions.isEmpty()) { "Offsetting positions remain open" }
            size
        }

    private fun <T : Any> poll(read: () -> Observable<T>, ready: (T) -> Boolean): Single<T> =
        Observable.defer(read)
            .onErrorResumeNext { error: Throwable ->
                if (error.isTransientReadFailure()) Observable.empty() else Observable.error(error)
            }
            .filter(ready)
            .repeatWhen { it.delay(1, TimeUnit.SECONDS, scheduler) }
            .firstOrError()
            .timeout(15, TimeUnit.SECONDS, scheduler)

    private fun Throwable.isTransientReadFailure(): Boolean = when (this) {
        is HttpException -> code() == 408 || code() == 429 || code() in 500..599
        is IOException -> true
        else -> false
    }

    class InitialOrderSubmissionException(cause: Throwable) : RuntimeException(cause)

    companion object {
        const val ORDER_SIZE_LIMIT = CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM

        private const val PRODUCT_CODE = "FX_BTC_JPY"
        private val finalOrderStates = setOf("COMPLETED", "CANCELED", "EXPIRED", "REJECTED")
    }
}
