package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendParentOrderRequest
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

internal class OrderRequestBodyConverterFactory : Converter.Factory() {
    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<Annotation>,
        methodAnnotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<*, RequestBody>? {
        if (type != SendChildOrderRequest::class.java && type != SendParentOrderRequest::class.java) return null
        val converter = retrofit.nextRequestBodyConverter<Any>(
            this, type, parameterAnnotations, methodAnnotations
        )
        return Converter<Any, RequestBody> { request ->
            OneShotOrderBody(checkNotNull(converter.convert(request)))
        }
    }

    private class OneShotOrderBody(private val body: RequestBody) : RequestBody() {
        override fun isOneShot() = true
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
}
