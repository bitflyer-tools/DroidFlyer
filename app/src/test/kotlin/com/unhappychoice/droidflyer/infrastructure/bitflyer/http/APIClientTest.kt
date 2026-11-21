package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.unhappychoice.droidflyer.domain.service.ClosePositionService
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendChildOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.SendParentOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopOrderRequest
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.request.StopSide
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderAcceptance
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentialCipher
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testCredentialKey
import io.reactivex.Observable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class APIClientTest {
    private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    private val credentials = APICredentials("dummy-key", "dummy-secret")
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
    fun orderPostIsNotResentAfterServiceUnavailableWithImmediateRetry() {
        val response = MockResponse.Builder().code(503).addHeader("Retry-After", "0").build()
        assertOrderSentOnce(response) { it is HttpException && it.code() == 503 }
    }

    @Test
    fun orderPostIsNotResentWhenServerClosesConnectionAfterReadingBody() {
        val response = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        assertOrderSentOnce(response) { it is IOException }
    }

    @Test
    fun stopOrderIsNotResentAfterServiceUnavailableWithImmediateRetry() {
        val response = MockResponse.Builder().code(503).addHeader("Retry-After", "0").build()
        assertStopOrderSentOnce(response) { it is HttpException && it.code() == 503 }
    }

    @Test
    fun stopOrderIsNotResentWhenServerClosesConnectionAfterReadingBody() {
        val response = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        assertStopOrderSentOnce(response) { it is IOException }
    }

    @Test
    fun orderAcceptanceIsUncertainWhenSuccessResponseHasNoAcceptanceId() {
        assertOrderSentOnce(MockResponse.Builder().body("{}").build()) { it is JsonParseException }
        assertOrderSentOnce(MockResponse.Builder().code(204).build()) { it is NullPointerException }
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
    fun publicMarketDataDoesNotReadOrSendSavedCredentials() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse.Builder().body("""{"mid_price":100,"bids":[],"asks":[]}""").build())
            origin.enqueue(MockResponse.Builder().body("[]").build())
            var credentialReads = 0
            val cipher = APICredentialCipher { create ->
                if (!create) credentialReads++
                testCredentialKey
            }
            val preference = APITokenPreference(RuntimeEnvironment.getApplication(), cipher)
            preference.save(credentials)

            val client = apiClient(origin, preference).client()
            client.getBoard("FX_BTC_JPY").test().assertComplete().assertNoErrors()
            client.getExecutionPrices("FX_BTC_JPY", 500).test().assertComplete().assertNoErrors()

            assertUnsignedRequest(origin, "/v1/board?product_code=FX_BTC_JPY")
            assertUnsignedRequest(origin, "/v1/executions?product_code=FX_BTC_JPY&count=500")
            assertEquals(0, credentialReads)
        }
    }

    @Test
    fun tradingSessionKeepsItsPinnedCredentialsAfterPreferencesChange() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse.Builder().body("""{"child_order_acceptance_id":"JRF-accepted"}""").build())
            val preference = credentialPreference()
            val session = apiClient(origin, preference).client(preference.read())

            preference.save(APICredentials("updated-key", "updated-secret"))
            session.sendChildOrder(order).test().assertResult(ChildOrderAcceptance("JRF-accepted"))

            assertSignedRequest(origin, "POST", "/v1/me/sendchildorder", gson.toJson(order))
        }
    }

    @Test
    fun settingsValidationSignsWithSubmittedCredentials() {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(MockResponse.Builder().body("[]").build())
            val submitted = APICredentials("submitted-key", "submitted-secret")

            client(origin).getBalance(submitted).test().assertResult(emptyList())

            assertSignedRequest(origin, "GET", "/v1/me/getbalance", "", submitted)
        }
    }

    @Test
    fun sharedClientSignsRequestsWithUpdatedCredentials() {
        MockWebServer().use { origin ->
            origin.start()
            repeat(2) { origin.enqueue(MockResponse.Builder().body("[]").build()) }
            val preference = credentialPreference()
            val client = client(origin, preference)
            val target = "/v1/me/getpermissions"

            client.getPermissions().test().assertComplete().assertNoErrors()
            assertSignedRequest(origin, "GET", target, "")

            val updated = APICredentials("updated-key", "updated-secret")
            preference.save(updated)
            client.getPermissions().test().assertComplete().assertNoErrors()
            assertSignedRequest(origin, "GET", target, "", updated)
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
                    it is OrderAcceptanceUncertainException && it.cause is JsonParseException
                }
                assertFalse(accepted)
                assertEquals((index + 1) * 2, origin.requestCount)
            }
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

    private fun assertOrderSentOnce(response: MockResponse, expectedCause: (Throwable?) -> Boolean) {
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(response)
            origin.enqueue(MockResponse.Builder().body("""{"child_order_acceptance_id":"retry"}""").build())

            client(origin).submitChildOrder(order).test()
                .assertNoValues()
                .assertError { it is OrderAcceptanceUncertainException && expectedCause(it.cause) }

            assertSignedRequest(origin, "POST", "/v1/me/sendchildorder", gson.toJson(order))
            assertEquals(1, origin.requestCount)
        }
    }

    private fun assertStopOrderSentOnce(response: MockResponse, expectedError: (Throwable) -> Boolean) {
        val stop = checkNotNull(StopOrderRequest.fromInput(StopSide.SELL, "0.01", "5000000"))
        val order = SendParentOrderRequest(stop)
        MockWebServer().use { origin ->
            origin.start()
            origin.enqueue(response)
            origin.enqueue(MockResponse.Builder().body("""{"parent_order_acceptance_id":"retry"}""").build())

            client(origin).sendParentOrder(order).test().assertNoValues().assertError { expectedError(it) }

            assertSignedRequest(origin, "POST", "/v1/me/sendparentorder", gson.toJson(order))
            assertEquals(1, origin.requestCount)
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

    private fun credentialPreference() =
        testAPITokenPreference(RuntimeEnvironment.getApplication()).apply { save(credentials) }

    private fun client(origin: MockWebServer, preference: APITokenPreference = credentialPreference()): APIClientV1 =
        apiClient(origin, preference).client()

    private fun apiClient(origin: MockWebServer, preference: APITokenPreference): APIClient {
        val transport = OkHttpClient.Builder().followRedirects(true).build()
        return APIClient(gson, preference, transport, origin.url("/v1/").toString())
    }

    private fun assertUnsignedRequest(origin: MockWebServer, target: String) {
        val request = checkNotNull(origin.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals(target, request.target)
        listOf("Access-Key", "Access-Timestamp", "Access-Sign").forEach { header ->
            assertNull(header, request.headers[header])
        }
    }

    private fun assertSignedRequest(
        origin: MockWebServer,
        method: String,
        target: String,
        body: String,
        expected: APICredentials = credentials
    ) {
        val request = checkNotNull(origin.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(method, request.method)
        assertEquals(target, request.target)
        assertEquals(body, request.body?.utf8().orEmpty())
        assertEquals(expected.key, request.headers["Access-Key"])
        val timestamp = request.headers["Access-Timestamp"]
        assertNotNull(timestamp?.toLongOrNull())
        assertEquals(
            (timestamp + method + target + body).toHmacSHA256(expected.secret),
            request.headers["Access-Sign"]
        )
    }
}
