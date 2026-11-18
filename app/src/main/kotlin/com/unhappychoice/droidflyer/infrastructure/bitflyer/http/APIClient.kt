package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class APIClient internal constructor(
    val gson: Gson,
    val preference: APITokenPreference,
    transport: OkHttpClient,
    private val baseUrl: String = "https://api.bitflyer.jp/v1/"
) {
    constructor(gson: Gson, preference: APITokenPreference) : this(gson, preference, OkHttpClient())

    fun client(): APIClientV1 = retrofit().create(APIClientV1::class.java)

    private val okHttp = transport.newBuilder()
        .followRedirects(false)
        .apply {
            interceptors().add(0, Interceptor { chain ->
                chain.proceed(authenticatedRequest(chain.request()))
            })
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private fun authenticatedRequest(request: Request): Request {
        val credentials = request.tag(APICredentials::class.java) ?: preference.read()
        val timestamp = (System.currentTimeMillis() / 1000L).toString()

        return request.newBuilder()
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .apply {
                if (credentials.key.isNotEmpty() && credentials.secret.isNotEmpty()) {
                    addHeader("Access-Key", credentials.key)
                    addHeader("Access-Timestamp", timestamp)
                    addHeader("Access-Sign", sign(request, timestamp, credentials.secret))
                }
            }.build()
    }

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .client(okHttp)
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .build()

    private fun sign(request: Request, timestamp: String, secret: String): String {
        val body = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
        val method = request.method
        val query = if (request.url.query != null) "?${request.url.query}" else ""
        val path = request.url.encodedPath + query
        return (timestamp + method + path + body).toHmacSHA256(secret)
    }
}
