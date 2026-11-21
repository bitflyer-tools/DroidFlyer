package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class APIClient internal constructor(
    val gson: Gson,
    val preference: APITokenPreference,
    transport: OkHttpClient,
    private val baseUrl: String = "https://api.bitflyer.jp/v1/"
) {
    constructor(gson: Gson, preference: APITokenPreference) : this(gson, preference, OkHttpClient())

    fun client(): APIClientV1 = defaultClient

    fun client(credentials: APICredentials): APIClientV1 = createClient(credentials)

    internal fun privateStatusClient(credentials: APICredentials): APIClientV1? =
        credentials.takeIf { it.isConfigured }?.let(::client)

    private val okHttp = transport.newBuilder()
        .followRedirects(false)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private val privatePathPrefix = checkNotNull(baseUrl.toHttpUrl().resolve("me/")).encodedPath

    private val defaultClient by lazy { createClient(null) }

    private fun authenticatedTransport(credentials: APICredentials?): OkHttpClient {
        val generation = preference.unlinkGeneration
        return okHttp.newBuilder()
            .apply {
                interceptors().add(0, Interceptor { chain ->
                    if (credentials != null && generation != preference.unlinkGeneration) {
                        throw IOException("bitFlyer account was unlinked")
                    }
                    chain.proceed(authenticatedRequest(chain.request(), credentials))
                })
            }
            .build()
    }

    private fun authenticatedRequest(request: Request, pinned: APICredentials?): Request {
        val builder = request.newBuilder()
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
        if (!request.url.encodedPath.startsWith(privatePathPrefix)) return builder.build()

        val credentials = pinned ?: request.tag(APICredentials::class.java) ?: preference.read()
        if (!credentials.isConfigured) return builder.build()

        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        return builder
            .addHeader("Access-Key", credentials.key)
            .addHeader("Access-Timestamp", timestamp)
            .addHeader("Access-Sign", sign(request, timestamp, credentials.secret))
            .build()
    }

    private fun createClient(credentials: APICredentials?): APIClientV1 = Retrofit.Builder()
        .client(authenticatedTransport(credentials))
        .baseUrl(baseUrl)
        .addConverterFactory(OrderRequestBodyConverterFactory())
        .addConverterFactory(GsonConverterFactory.create(gson))
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .build()
        .create(APIClientV1::class.java)

    private fun sign(request: Request, timestamp: String, secret: String): String {
        val body = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
        val method = request.method
        val query = if (request.url.query != null) "?${request.url.query}" else ""
        val path = request.url.encodedPath + query
        return (timestamp + method + path + body).toHmacSHA256(secret)
    }
}
