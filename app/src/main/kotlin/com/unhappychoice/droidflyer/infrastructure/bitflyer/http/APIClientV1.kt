package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.CancelChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.*
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import io.reactivex.Completable
import io.reactivex.Observable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Tag

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

    @GET("executions")
    fun getExecutionPrices(
        @Query("product_code") productCode: String,
        @Query("count") count: Int
    ): Observable<List<ExecutionPrice>>

    @GET("gethealth")
    fun getHealth(): Observable<Map<String, Any>>

    @GET("getchats")
    fun getChats(): Observable<List<Map<String, Any>>>

    // Private API

    @GET("me/getpermissions")
    fun getPermissions(): Observable<List<String>>

    @GET("me/getbalance")
    fun getBalance(@Tag credentials: APICredentials? = null): Observable<List<Map<String, Any>>>

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

    @GET("me/getdeposits")
    fun getDeposits(): Observable<List<Map<String, Any>>>

    // @POST("POST /v1/me/withdraw")
    // fun withdraw(): Observable<Map<String, Any>>

    @GET("me/getwithdrawals")
    fun getWithdrawals(): Observable<List<Map<String, Any>>>

    @POST("me/sendchildorder")
    fun sendChildOrder(@Body request: SendChildOrderRequest): Observable<ChildOrderAcceptance>

    @POST("me/cancelchildorder")
    fun cancelChildOrder(@Body request: CancelChildOrderRequest): Completable

    // @POST("me/sendparentorder")
    // fun sendParentOrder()

    // @POST("me/cancelparentorder")
    // fun cancelParentOrder(@Body request: CancelParentOrderRequest): Observable<Map<String, Any>>

    // @POST("me/cancelallchildorders")
    // fun cancelAllChildOrders(@Body request: CancellAllChildOrderRequest): Observable<Map<String, Any>>

    @GET("me/getchildorders")
    fun getChildOrders(
        @Query("product_code") productCode: String = "BTC_JPY",
        @Query("child_order_state") orderState: String? = "ACTIVE",
        @Query("child_order_acceptance_id") acceptanceId: String? = null,
        @Query("count") count: Int? = null
    ): Observable<List<ChildOrder>>

    // @GET("me/getparentorders")
    // fun getParentOrders(//): Observable<List<Map<String, Any>>>

    // @GET("me/getparentorder")
    // fun getParentOrder(//): Observable<Map<String, Any>>

    @GET("me/getexecutions")
    fun getMyExecutions(
        @Query("count") count: Int,
        @Query("before") before: Long? = null,
        @Query("product_code") productCode: String = "FX_BTC_JPY"
    ): Observable<List<PrivateExecution>>

    @GET("me/getpositions")
    fun getPositions(
        @Query("product_code") productCode: String = "FX_BTC_JPY"
    ): Observable<List<Position>>

    @GET("me/getcollateralhistory")
    fun getCollateralHistory(): Observable<List<Map<String, Any>>>

    @GET("me/gettradingcommission")
    fun getTradingCommision(): Observable<Map<String, Any>>
}
