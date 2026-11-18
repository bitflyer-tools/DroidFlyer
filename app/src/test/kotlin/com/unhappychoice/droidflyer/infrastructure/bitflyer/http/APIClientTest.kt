package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class APIClientTest {
    @Test
    fun sharedClientSignsRequestsWithUpdatedCredentials() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/me/getpermissions") { exchange ->
            val headers = exchange.requestHeaders
            val body = Gson().toJson(
                listOf("Access-Key", "Access-Timestamp", "Access-Sign").map(headers::getFirst)
            ).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val application = RuntimeEnvironment.getApplication()
            val settings = APITokenPreference(application)
            settings.key = "first-key"
            settings.secret = "first-secret"
            val client = APIClient(Gson(), APITokenPreference(application), "http://127.0.0.1:${server.address.port}/v1/")
                .client()

            assertCredentials(client, "first-key", "first-secret")
            settings.key = "updated-key"
            settings.secret = "updated-secret"
            assertCredentials(client, "updated-key", "updated-secret")
        } finally {
            server.stop(0)
        }
    }

    private fun assertCredentials(client: APIClientV1, expectedKey: String, expectedSecret: String) {
        val (key, timestamp, signature) = client.getPermissions().blockingFirst()
        assertEquals(expectedKey, key)
        assertEquals("${timestamp}GET/v1/me/getpermissions".toHmacSHA256(expectedSecret), signature)
    }
}
