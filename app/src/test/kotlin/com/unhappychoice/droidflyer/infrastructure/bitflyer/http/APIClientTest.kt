package com.unhappychoice.droidflyer.infrastructure.bitflyer.http

import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class APIClientTest {
    @Test
    fun sharedClientSignsRequestsWithUpdatedCredentials() {
        val application = RuntimeEnvironment.getApplication()
        val settings = APITokenPreference(application)
        val first = APICredentials("first-key", "first-secret")
        val updated = APICredentials("updated-key", "updated-secret")
        settings.save(first)
        val client = APIClient(Gson(), APITokenPreference(application), echoSignedHeaders()).client()

        assertCredentials(client, first)
        settings.save(updated)
        assertCredentials(client, updated)
    }

    private fun echoSignedHeaders(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val headers = listOf("Access-Key", "Access-Timestamp", "Access-Sign").map(request::header)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(Gson().toJson(headers).toResponseBody())
            .build()
    }.build()

    private fun assertCredentials(client: APIClientV1, expected: APICredentials) {
        val (key, timestamp, signature) = client.getPermissions().blockingFirst()
        assertEquals(expected.key, key)
        assertEquals("${timestamp}GET/v1/me/getpermissions".toHmacSHA256(expected.secret), signature)
    }
}
