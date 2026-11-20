package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import io.reactivex.Observable
import retrofit2.HttpException
import java.io.IOException

internal fun APIClientV1.submitChildOrder(request: SendChildOrderRequest): Observable<String> =
    Observable.defer { sendChildOrder(request) }
        .map { response ->
            val acceptanceId = response["child_order_acceptance_id"] as? String
            if (acceptanceId.isNullOrBlank()) throw OrderAcceptanceUncertainException()
            acceptanceId
        }
        .onErrorResumeNext { error: Throwable ->
            Observable.error(if (error.leavesAcceptanceUncertain()) OrderAcceptanceUncertainException(error) else error)
        }

internal class OrderAcceptanceUncertainException(cause: Throwable? = null) :
    RuntimeException("Order acceptance could not be confirmed", cause)

private fun Throwable.leavesAcceptanceUncertain(): Boolean = when (this) {
    is HttpException -> code() == 408 || code() in 500..599
    // Retrofit's RxJava adapter can emit a null body for an empty successful response.
    is IOException, is JsonParseException, is IllegalStateException, is NullPointerException -> true
    else -> false
}
