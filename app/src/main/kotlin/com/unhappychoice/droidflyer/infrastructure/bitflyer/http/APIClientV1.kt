package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import io.reactivex.Observable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface APIClientV1 {
    // Public API

    @GET("markets")
    fun getMarkets(): Observable<List<Map<String, Any>>>

    @GET("board")
    fun getBoard(
        @Query("product_code") productCode: String = "BTC_JPY"
    ): Observable<Board>

    @GET("ticker")
    fun getTicker(
        @Query("product_code") productCode: String = "BTC_JPY"
    ): Observable<Ticker>

    @GET("executions")
    fun getExecutions(
        @Query("product_code") productCode: String = "BTC_JPY"
    ): Observable<List<Execution>>

    @GET("gethealth")
    fun getHealth(): Observable<Map<String, Any>>

    @GET("getchats")
    fun getChats(): Observable<List<Map<String, Any>>>

    // Private API

    @GET("me/getpermissions")
    fun getPermissions(): Observable<List<String>>

    @GET("me/getbalance")
    fun getBalance(): Observable<List<Map<String, Any>>>

    @GET("me/getcollateral")
    fun getCollateral(): Observable<Map<String, Any>>

    @GET("me/getaddresses")
    fun getAddresses(): Observable<List<Map<String, Any>>>

    @GET("me/getcoinins")
    fun getCoinins(): Observable<List<Map<String, Any>>>

    @GET("me/getcoinouts")
    fun getCoinouts(): Observable<List<Map<String, Any>>>

    @GET("me/getbankaccounts")
    fun getBankAccounts(): Observable<List<Map<String, Any>>>

    @GET("/me/getdeposits")
    fun getDeposits(): Observable<List<Map<String, Any>>>

    // @POST("POST /v1/me/withdraw")
    // fun withdraw(): Observable<Map<String, Any>>

    @GET("me/getwithdrawals")
    fun getWithdrawals(): Observable<List<Map<String, Any>>>

    @POST("me/sendchildorder")
    fun sendChildOrder(@Body request: SendChildOrderRequest): Observable<Map<String, Any>>

    // @POST("me/cancelchildorder")
    // fun cancelChildOrder(@Body request: CancelChildOrderRequest): Observable<Map<String, Any>>

    // @POST("me/sendparentorder")
    // fun sendParentOrder()

    // @POST("me/cancelparentorder")
    // fun cancelParentOrder(@Body request: CancelParentOrderRequest): Observable<Map<String, Any>>

    // @POST("me/cancelallchildorders")
    // fun cancelAllChildOrders(@Body request: CancellAllChildOrderRequest): Observable<Map<String, Any>>

    @GET("me/getchildorders")
    fun getChildOrders(
        @Query("product_code") productCode: String = "BTC_JPY",
        @Query("child_order_state") orderState: String = "ACTIVE"
    ): Observable<List<ChildOrder>>

    // @GET("me/getparentorders")
    // fun getParentOrders(//): Observable<List<Map<String, Any>>>

    // @GET("me/getparentorder")
    // fun getParentOrder(//): Observable<Map<String, Any>>

    // @GET("me/getexecutions")
    // fun getExecutions(//): Observable<List<Map<String, Any>>>

    @GET("me/getpositions")
    fun getPositions(
        @Query("product_code") productCode: String = "FX_BTC_JPY"
    ): Observable<List<Position>>

    @GET("me/getmycollateralhistory")
    fun getCollateralHistory(): Observable<List<Map<String, Any>>>

    @GET("me/gettradingcommission")
    fun getTradingCommision(): Observable<Map<String, Any>>
}