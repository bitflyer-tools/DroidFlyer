package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import io.reactivex.rxjava3.core.Observable
import retrofit2.HttpException
import java.io.IOException

internal fun APIClientV1.submitChildOrder(request: SendChildOrderRequest): Observable<ChildOrderAcceptance> =
    Observable.defer { sendChildOrder(request) }
        // Retrofit can emit a null body for an empty successful response.
        .map { acceptance: ChildOrderAcceptance? ->
            acceptance ?: throw NullPointerException("Missing order acceptance body")
        }
        .onErrorResumeNext { error: Throwable ->
            Observable.error(if (error.leavesAcceptanceUncertain()) OrderAcceptanceUncertainException(error) else error)
        }

internal class OrderAcceptanceUncertainException(cause: Throwable? = null) :
    RuntimeException("Order acceptance could not be confirmed", cause)

private fun Throwable.leavesAcceptanceUncertain(): Boolean = when (this) {
    is HttpException -> code() == 408 || code() in 500..599
    is IOException, is JsonParseException, is IllegalStateException, is NullPointerException -> true
    else -> false
}
