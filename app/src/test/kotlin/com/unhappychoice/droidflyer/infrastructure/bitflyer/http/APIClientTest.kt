package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.Observable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val invalidAcceptanceBodies = listOf(
        "{}",
        """{"child_order_acceptance_id":""}""",
        """{"child_order_acceptance_id":"  "}""",
        """{"child_order_acceptance_id":123}""",
        """{"child_order_acceptance_id":true}""",
        """{"child_order_acceptance_id":null}""",
        """{"child_order_acceptance_id":{}}"""
    )

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
            origin.enqueue(MockResponse.Builder().body("""{"child_order_acceptance_id":"JRF-accepted"}""").build())

            val client = client(origin)
            client.getPositions().test().assertComplete().assertNoErrors()
            client.sendChildOrder(order).test().assertResult(ChildOrderAcceptance("JRF-accepted"))

            assertSignedRequest(origin, "GET", "/v1/me/getpositions?product_code=FX_BTC_JPY", "")
            assertSignedRequest(origin, "POST", "/v1/me/sendchildorder", gson.toJson(order))
        }
    }

    @Test
    fun invalidAcceptanceResponsesFailAtTheRestBoundary() {
        MockWebServer().use { origin ->
            origin.start()
            val client = client(origin)

            invalidAcceptanceBodies.forEach { body ->
                origin.enqueue(MockResponse.Builder().body(body).build())
                client.sendChildOrder(order).test().assertFailure(JsonParseException::class.java)
            }
        }
    }

    @Test
    fun invalidAcceptanceResponsesDoNotNotifyAcceptanceOrPollForFills() {
        MockWebServer().use { origin ->
            origin.start()
            val service = ClosePositionService(client(origin))

            invalidAcceptanceBodies.forEachIndexed { index, body ->
                origin.enqueue(MockResponse.Builder().body("""[{"side":"BUY","size":0.5}]""").build())
                origin.enqueue(MockResponse.Builder().body(body).build())
                var accepted = false

                service.close(0.5) { accepted = true }.test().assertError {
                    it is ClosePositionService.InitialOrderSubmissionException && it.cause is JsonParseException
                }
                assertFalse(accepted)
                assertEquals((index + 1) * 2, origin.requestCount)
            }
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
        val preference = APITokenPreference(RuntimeEnvironment.getApplication()).apply {
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
