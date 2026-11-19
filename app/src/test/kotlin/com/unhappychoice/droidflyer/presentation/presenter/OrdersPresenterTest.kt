package com.unhappychoice.droidflyer.presentation.presenter

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.PrivateTradingState
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.PrivateExecution
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.lang.reflect.Proxy
import java.math.BigDecimal

class OrdersPresenterTest {
    @Before
    fun useSynchronousSchedulers() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setInitMainThreadSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
        RxAndroidPlugins.reset()
    }

    @Test
    fun reportsNetworkFailureAndAllowsRetry() {
        assertFailureAllowsRetry { throw IOException("Connection lost") }
    }

    @Test
    fun reportsHttpRejectionAndAllowsRetry() {
        assertFailureAllowsRetry { response(it, 400) }
    }

    @Test
    fun switchingAccountsClearsFillsAndIgnoresPendingResponses() {
        val scheduler = TestScheduler()
        val oldResponses = PublishSubject.create<List<PrivateExecution>>()
        val newResponses = PublishSubject.create<List<PrivateExecution>>()
        val state = PrivateTradingState(fillApi(oldResponses), scheduler, scheduler)
        val presenter = OrdersPresenter(state) {}
        val accountChanges = presenter.fillAccountChanges.test()
        try {
            val history = presenter.fillHistory
            val oldFill = PrivateExecution(1, "BUY", BigDecimal.ONE, BigDecimal.ONE, "2026-01-01T00:00:00")
            history.refresh()
            scheduler.triggerActions()
            oldResponses.onNext(listOf(oldFill))
            scheduler.triggerActions()
            assertEquals(listOf(oldFill), history.state.value.fills)

            history.refresh()
            scheduler.triggerActions()
            oldResponses.onNext(listOf(oldFill))
            state.switchAccount(fillApi(newResponses))
            assertEquals(FillHistory.State(), history.state.value)

            history.refresh()
            scheduler.triggerActions()
            assertEquals(emptyList<PrivateExecution>(), history.state.value.fills)
            val newFill = oldFill.copy(id = 2)
            newResponses.onNext(listOf(newFill))
            scheduler.triggerActions()
            assertEquals(listOf(newFill), history.state.value.fills)
        } finally {
            accountChanges.dispose()
        }
    }

    private fun fillApi(responses: Observable<List<PrivateExecution>>): APIClientV1 =
        Proxy.newProxyInstance(APIClientV1::class.java.classLoader, arrayOf(APIClientV1::class.java)) { _, method, _ ->
            when (method.name) {
                "getMyExecutions" -> responses.take(1)
                "getPositions", "getCollateral", "getChildOrders" -> Observable.empty<Any>()
                else -> error("Unexpected API call: ${method.name}")
            }
        } as APIClientV1

    private fun assertFailureAllowsRetry(failureResponse: (Request) -> Response) {
        val order = activeOrder()
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            if (requests.size == 1) failureResponse(request) else response(request, 200)
        }.build()
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val api = Retrofit.Builder()
            .baseUrl("https://example.test/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .build()
            .create(APIClientV1::class.java)
        val state = PrivateTradingState(api)
        state.orders.value = listOf(order)
        val presenter = OrdersPresenter(state) {
            state.orders.value = emptyList()
        }
        val failures = presenter.cancellationFailures.test()
        val orders = presenter.orders.test()

        presenter.cancelOrder(order)

        failures.assertValue(order).assertNoErrors().assertNotComplete()
        orders.assertValues(listOf(order))

        presenter.cancelOrder(orders.values().last().single())

        val expectedBody = """{"product_code":"FX_BTC_JPY","child_order_acceptance_id":"acceptance-1"}"""
        assertEquals(listOf(expectedBody, expectedBody), requests.map { request ->
            Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
        })
        failures.assertValueCount(1)
        orders.assertValues(listOf(order), emptyList())
    }

    private fun response(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body("".toResponseBody())
            .build()

    private fun activeOrder(): ChildOrder =
        ChildOrder(
            id = 1,
            childOrderId = "order-1",
            productCode = "FX_BTC_JPY",
            side = "BUY",
            childOrderType = "LIMIT",
            price = 100,
            average_price = 0,
            size = 0.1,
            childOrderState = "ACTIVE",
            childOrderAcceptanceId = "acceptance-1",
            outstandingSize = 0.1,
            cancelSize = 0.0,
            executedSize = 0.0,
            totalCommission = 0.0
        )
}
