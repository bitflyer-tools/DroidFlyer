package com.unhappychoice.droidflyer.domain.service

import com.unhappychoice.droidflyer.extension.round
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.wholeSize
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sign

internal class ClosePositionService(
    private val apiClient: APIClientV1,
    private val scheduler: Scheduler = Schedulers.io()
) {
    fun close(confirmedSize: Double): Single<Unit> =
        positionSize().flatMap { size -> closeRemaining(size, confirmedSize) }

    private fun closeRemaining(size: Double, confirmedSize: Double): Single<Unit> {
        if (size == 0.0) return Single.just(Unit)
        if (size.sign != confirmedSize.sign || abs(size) > abs(confirmedSize)) {
            return Single.error(IllegalStateException("Position changed since confirmation"))
        }

        val side = if (size > 0.0) "SELL" else "BUY"
        val request = SendChildOrderRequest(
            PRODUCT_CODE, "MARKET", side, null, abs(size).coerceAtMost(ORDER_SIZE_LIMIT), timeInForce = "IOC"
        )
        return apiClient.sendChildOrder(request).singleOrError()
            .flatMap { response ->
                val acceptanceId = response["child_order_acceptance_id"] as? String
                check(!acceptanceId.isNullOrBlank()) { "Missing close order acceptance ID" }
                executedSize(acceptanceId)
            }
            .flatMap { filled ->
                check(filled > 0.0 && filled <= request.size) { "Invalid close order fill size" }
                val expected = (abs(size) - filled).round(8)
                poll({ positionSize().toObservable() }) {
                    abs(it) <= expected || it.sign != size.sign
                }.flatMap { remaining -> closeRemaining(remaining, size) }
            }
    }

    private fun executedSize(acceptanceId: String): Single<Double> =
        poll({ apiClient.getChildOrders(PRODUCT_CODE, null, acceptanceId).flatMapIterable { it } }) {
            it.childOrderAcceptanceId == acceptanceId &&
                it.childOrderState in finalOrderStates && it.outstandingSize == 0.0
        }.map { it.executedSize }

    private fun positionSize(): Single<Double> =
        apiClient.getPositions(PRODUCT_CODE).singleOrError().map { it.wholeSize() }

    private fun <T : Any> poll(read: () -> Observable<T>, ready: (T) -> Boolean): Single<T> =
        Observable.defer(read)
            .filter(ready)
            .repeatWhen { it.delay(1, TimeUnit.SECONDS, scheduler) }
            .firstOrError()
            .timeout(15, TimeUnit.SECONDS, scheduler)

    companion object {
        const val ORDER_SIZE_LIMIT = 10.0

        private const val PRODUCT_CODE = "FX_BTC_JPY"
        private val finalOrderStates = setOf("COMPLETED", "CANCELED", "EXPIRED", "REJECTED")
    }
}
