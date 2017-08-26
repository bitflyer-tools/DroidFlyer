package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

class APIClient(val gson: Gson, val preference: APITokenPreference) {

    private val key: String
        get() = preference.key

    private val secret: String
        get() = preference.secret

    fun client(): APIClientV1 = retrofit().create(APIClientV1::class.java)

    private val baseUrl = "https://api.bitflyer.jp/v1/"

    private val okHttp = OkHttpClient.Builder().addInterceptor {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()

        val builder = it.request().newBuilder()
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .apply { if(key != "" && secret != "") addHeader("Access-Key", key) }
            .apply { if(key != "" && secret != "") addHeader("Access-Timestamp", timestamp) }
            .apply { if(key != "" && secret != "") addHeader("Access-Sign", sign(it.request(), timestamp)) }
            .build()

        it.proceed(builder)
    }.build()

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .client(okHttp)
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
        .build()

    private fun sign(request: Request, timestamp: String): String {
        val body = Buffer().apply { request.body()?.writeTo(this) }.readUtf8()
        val method = request.method()
        val query = if (request.url().query() != null) "?${request.url().query()}" else ""
        val path = request.url().encodedPath() + query
        return (timestamp + method + path + body).toHmacSHA256(secret)
    }
}