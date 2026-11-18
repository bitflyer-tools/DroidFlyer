package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.content.Context
import com.google.gson.Gson
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsPresenterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val preference = APITokenPreference(context)
    private val requests = mutableListOf<Request>()
    private val authenticated = mutableListOf<Pair<String, String>>()
    private var validationStatus = 200
    private var validationFailure: IOException? = null

    private val api = APIClient(Gson(), APITokenPreference(context), OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val validating = request.url.encodedPath.endsWith("/getbalance")
            if (validating) {
                assertEquals("saved-key" to "saved-secret", savedCredentials())
                validationFailure?.let { throw it }
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (validating) validationStatus else 200)
                .message("Test response")
                .body("[]".toResponseBody())
                .build()
        }.build()).client()
    private val presenter = SettingsPresenter(api, preference) { authenticated.add(savedCredentials()) }

    @Before
    fun setUp() {
        context.getSharedPreferences("api_token", Context.MODE_PRIVATE).edit()
            .putString("key", "saved-key")
            .putString("secret", "saved-secret")
            .apply()
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { Schedulers.trampoline() }
        presenter.key.value = "submitted-key"
        presenter.secret.value = "submitted-secret"
    }

    @After
    fun resetSchedulers() {
        RxAndroidPlugins.reset()
        RxJavaPlugins.reset()
    }

    @Test
    fun rejectedCredentialsKeepTheSavedPair() {
        validationStatus = 401
        presenter.saveCredentials().test().assertError(HttpException::class.java)
        assertSavedPairStillActive()
    }

    @Test
    fun failedValidationRequestKeepsTheSavedPair() {
        val failure = IOException("Connection failed")
        validationFailure = failure
        presenter.saveCredentials().test().assertError(failure)
        assertSavedPairStillActive()
    }

    @Test
    fun successfulValidationSavesAndAuthenticatesWithTheSubmittedPair() {
        val save = presenter.saveCredentials()
        presenter.key.value = "edited-key"
        presenter.secret.value = "edited-secret"

        save.test().assertValueCount(1).assertComplete()
        api.getPermissions().blockingFirst()

        assertEquals("submitted-key" to "submitted-secret", savedCredentials())
        assertEquals(listOf(savedCredentials()), authenticated)
        assertSignedWith(requests[0], "submitted-key", "submitted-secret")
        assertSignedWith(requests[1], "submitted-key", "submitted-secret")
    }

    private fun assertSavedPairStillActive() {
        api.getPermissions().blockingFirst()
        assertEquals("saved-key" to "saved-secret", savedCredentials())
        assertEquals(emptyList<Pair<String, String>>(), authenticated)
        assertSignedWith(requests[0], "submitted-key", "submitted-secret")
        assertSignedWith(requests[1], "saved-key", "saved-secret")
    }

    private fun savedCredentials() = preference.read().let { it.key to it.secret }

    private fun assertSignedWith(request: Request, key: String, secret: String) {
        assertEquals(key, request.header("Access-Key"))
        val signed = request.header("Access-Timestamp") + request.method + request.url.encodedPath
        assertEquals(signed.toHmacSHA256(secret), request.header("Access-Sign"))
    }
}
