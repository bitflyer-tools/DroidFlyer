package com.unhappychoice.droidflyer.infrastructure.bitflyer

import io.reactivex.Observable
import retrofit2.http.GET

interface APIClientV1 {
    @GET("markets")
    fun getMarkets(): Observable<List<Map<String, Any>>>

    @GET("me/getpermissions")
    fun getPermissions(): Observable<List<String>>
}
