package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import io.reactivex.Observable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class APIClientTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val order = SendChildOrderRequest(childOrderType = "LIMIT", side = "BUY", price = 100L, size = 0.01)

    @Test
    fun authenticatedGetDoesNotFollowCrossOriginRedirect() {
        assertRedirectBlocked(302, "[]") { it.getPositions() }
    }

    @Test
    fun orderPostDoesNotFollowCrossOriginRedirects() {
        listOf(307, 308).forEach { status ->
            assertRedirectBlocked(status, "{}") { it.sendChildOrder(order) }
        }
    }

    @Test
    fun directRequestsCarrySignedAccessHeaders() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse.Builder().body("[]").build())
            origin.enqueue(MockResponse.Builder().body("{}").build())

            val client = client(origin)
            client.getPositions().test().assertComplete().assertNoErrors()
            client.sendChildOrder(order).test().assertComplete().assertNoErrors()

            assertSignedRequest(origin, "GET", "/v1/me/getpositions?product_code=FX_BTC_JPY", "")
            assertSignedRequest(origin, "POST", "/v1/me/sendchildorder", gson.toJson(order))
        }
    }

    @Test
    fun collateralHistoryUsesDocumentedEndpointAndDecodesResponse() {
        MockWebServer().use { origin ->
            val body = """
                [{"id":42,"currency_code":"BTC","change":-0.25,"amount":1.5,
                  "reason_code":"EXCHANGE_COLL","date":"2025-01-15T12:30:45.123"}]
            """.trimIndent()
            origin.start()
            origin.enqueue(MockResponse.Builder().body(body).build())

            val history = client(origin).getCollateralHistory().blockingSingle()

            assertSignedRequest(origin, "GET", "/v1/me/getcollateralhistory", "")
            val entry = mapOf(
                "id" to 42.0,
                "currency_code" to "BTC",
                "change" to -0.25,
                "amount" to 1.5,
                "reason_code" to "EXCHANGE_COLL",
                "date" to "2025-01-15T12:30:45.123"
            )
            assertEquals(listOf(entry), history)
        }
    }

    private fun assertRedirectBlocked(
        status: Int,
        responseBody: String,
        request: (APIClientV1) -> Observable<*>
    ) {
        MockWebServer().use { origin ->
            MockWebServer().use { destination ->
                origin.start()
                destination.start()
                destination.enqueue(MockResponse.Builder().body(responseBody).build())
                origin.enqueue(
                    MockResponse.Builder()
                        .code(status)
                        .addHeader("Location", destination.url("/redirect"))
                        .build()
                )

                val result = request(client(origin)).test()
                assertEquals(0, destination.requestCount)
                result.assertError { it is HttpException && it.code() == status }
                assertEquals(1, origin.requestCount)
            }
        }
    }

    private fun client(origin: MockWebServer): APIClientV1 {
        val preference = testAPITokenPreference(RuntimeEnvironment.getApplication()).apply {
            save(APICredentials("dummy-key", "dummy-secret"))
        }
        val transport = OkHttpClient.Builder().followRedirects(true).build()
        return APIClient(gson, preference, transport, origin.url("/v1/").toString()).client()
    }

    private fun assertSignedRequest(origin: MockWebServer, method: String, target: String, body: String) {
        val request = checkNotNull(origin.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(method, request.method)
        assertEquals(target, request.target)
        assertEquals(body, request.body?.utf8().orEmpty())
        assertEquals("dummy-key", request.headers["Access-Key"])
        val timestamp = request.headers["Access-Timestamp"]
        assertNotNull(timestamp?.toLongOrNull())
        assertEquals(
            (timestamp + method + target + body).toHmacSHA256("dummy-secret"),
            request.headers["Access-Sign"]
        )
    }
}
