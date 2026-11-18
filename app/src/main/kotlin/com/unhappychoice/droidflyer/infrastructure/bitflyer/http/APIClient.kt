package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class APIClient(val gson: Gson, val preference: APITokenPreference) {

    fun client(): APIClientV1 = createClient(preference::credentials)

    fun client(credentials: Credentials): APIClientV1 = createClient { credentials }

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private fun createClient(credentials: () -> Credentials): APIClientV1 {
        val client = okHttp.newBuilder()
            .addInterceptor { chain -> chain.proceed(authenticatedRequest(chain.request(), credentials())) }
            .build()
        return Retrofit.Builder()
            .client(client)
            .baseUrl("https://api.bitflyer.jp/v1/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)
    }

    private fun authenticatedRequest(request: Request, token: Credentials): Request {
        val builder = request.newBuilder()
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
        if (token.isConfigured()) {
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            builder.addHeader("Access-Key", token.key)
                .addHeader("Access-Timestamp", timestamp)
                .addHeader("Access-Sign", sign(request, timestamp, token.secret))
        }
        return builder.build()
    }

    private fun sign(request: Request, timestamp: String, secret: String): String {
        val body = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
        val method = request.method
        val query = if (request.url.query != null) "?${request.url.query}" else ""
        val path = request.url.encodedPath + query
        return (timestamp + method + path + body).toHmacSHA256(secret)
    }
}
