package com.unhappychoice.droidflyer.presentation.presenter

import android.app.Application
import android.content.Context
import com.google.gson.Gson
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.toHmacSHA256
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentialCipher
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.testCredentialKey
import com.unhappychoice.droidflyer.presentation.view.OrderView
import io.reactivex.Observable
import io.reactivex.android.plugins.RxAndroidPlugins
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.TestScheduler
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
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
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsPresenterTest {
    private val context = RuntimeEnvironment.getApplication()
    private var credentialKey: SecretKeySpec? = testCredentialKey
    private val replacementKey = SecretKeySpec(ByteArray(32) { 1 }, "AES")
    private val preference = APITokenPreference(context, APICredentialCipher { create ->
        credentialKey ?: if (create) replacementKey.also { credentialKey = it }
        else error("API credential encryption key is missing")
    })
    private val requests = mutableListOf<Request>()
    private val authenticated = mutableListOf<Pair<String, String>>()
    private val switched = mutableListOf<APIClientV1?>()
    private var validationStatus = 200
    private var validationFailure: IOException? = null
    private var expectedSavedCredentials = "saved-key" to "saved-secret"

    private val api = APIClient(Gson(), preference, OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val validating = request.url.encodedPath.endsWith("/getbalance")
            if (validating) {
                assertEquals(expectedSavedCredentials, savedCredentials())
                validationFailure?.let { throw it }
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (validating) validationStatus else 200)
                .message("Test response")
                .body(when (request.url.encodedPath) {
                    "/v1/board" -> """{"midPrice":100.0,"bids":[],"asks":[]}"""
                    "/v1/me/getcollateral" -> """{"collateral":250.0}"""
                    else -> "[]"
                }.toResponseBody())
                .build()
        }.build())
    private val presenter = SettingsPresenter(api, preference, { switched.add(it) }) { authenticated.add(savedCredentials()) }

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
        api.client().getPermissions().blockingFirst()

        assertEquals("submitted-key" to "submitted-secret", savedCredentials())
        assertEquals(listOf(savedCredentials()), authenticated)
        assertSignedWith(requests[0], "submitted-key", "submitted-secret")
        assertSignedWith(requests[1], "submitted-key", "submitted-secret")
    }

    @Test
    fun missingEncryptionKeyAllowsCredentialsToBeRegisteredAgain() {
        preference.save(APICredentials("saved-key", "saved-secret"))
        val stored = context.getSharedPreferences("api_token", Context.MODE_PRIVATE)
        val originalEntries = stored.all
        credentialKey = null
        expectedSavedCredentials = "" to ""

        presenter.loadCredentials()
        assertEquals("", presenter.key.value)
        assertEquals("", presenter.secret.value)
        presenter.key.value = "submitted-key"
        presenter.secret.value = "submitted-secret"

        validationStatus = 401
        presenter.saveCredentials().test().assertError(HttpException::class.java)
        assertEquals(originalEntries, stored.all)
        assertEquals(emptyList<APIClientV1?>(), switched)
        assertEquals(emptyList<Pair<String, String>>(), authenticated)

        validationStatus = 200
        presenter.saveCredentials().test().assertValueCount(1).assertComplete()
        assertEquals("submitted-key" to "submitted-secret", savedCredentials())
        assertEquals(1, switched.size)
        assertEquals(listOf(savedCredentials()), authenticated)
    }

    @Test
    fun switchedClientKeepsTheValidatedCredentials() {
        presenter.saveCredentials().test().assertComplete()
        preference.save(APICredentials("other-key", "other-secret"))

        requireNotNull(switched.single()).getPermissions().blockingFirst()

        assertSignedWith(requests[1], "submitted-key", "submitted-secret")
    }

    @Test
    fun savingTheSameCredentialsKeepsTheCurrentSession() {
        presenter.key.value = "saved-key"
        presenter.secret.value = "saved-secret"

        presenter.saveCredentials().test().assertComplete()

        assertEquals(emptyList<APIClientV1?>(), switched)
        assertEquals(listOf(savedCredentials()), authenticated)
    }

    @Test
    fun privateStatusStartsAfterValidationWhilePublicBoardLoadsBeforeSetup() {
        context.getSharedPreferences("api_token", Context.MODE_PRIVATE).edit().clear().apply()
        expectedSavedCredentials = "" to ""
        val timer = TestScheduler()
        RxJavaPlugins.setComputationSchedulerHandler { timer }
        RxJavaPlugins.setSingleSchedulerHandler { timer }
        val status = createStatus()
        assertEquals(listOf("/v1/board"), requestPaths())
        val scope = MortarScope.buildRootScope()
            .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
            .build("order_status_test")
        try {
            OrderPresenter(status).takeView(OrderView(scope.createContext(context), null))
            timer.advanceTimeBy(18, TimeUnit.SECONDS)
            assertEquals(List(4) { "/v1/board" }, requestPaths())
            assertEquals(100.0, status.board.value.midPrice, 0.0)

            val settings = SettingsPresenter(api, preference, status::switchAccount) {}
            settings.key.value = "submitted-key"
            settings.secret.value = "submitted-secret"
            settings.saveCredentials().test().assertValueCount(1).assertComplete()

            assertEquals(
                listOf(
                    "/v1/me/getbalance", "/v1/me/getpositions",
                    "/v1/me/getcollateral", "/v1/me/getchildorders", "/v1/board"
                ),
                requestPaths().drop(4)
            )
            val privateRequests = requests.drop(4).filter { it.url.encodedPath.startsWith("/v1/me/") }
            privateRequests.forEach { assertSignedWith(it, "submitted-key", "submitted-secret") }
            assertEquals("ACTIVE", privateRequests.last().url.queryParameter("child_order_state"))
            assertEquals(250L, status.tradingTotals.blockingFirst().balance)
        } finally {
            status.dispose()
            scope.destroy()
        }
    }

    @Test
    fun privateStatusRequiresBothPartsOfTheSavedCredentials() {
        listOf(APICredentials("key", ""), APICredentials("", "secret")).forEach {
            preference.save(it)
            createStatus().dispose()
        }
        assertEquals(List(2) { "/v1/board" }, requestPaths())
    }

    private fun createStatus() = CurrentStatusService(
        api.client(), Observable.empty(), Observable.empty(), Observable.empty(),
        api.privateStatusClient(preference.read())
    )

    private fun requestPaths() = requests.map { it.url.encodedPath }

    private fun assertSavedPairStillActive() {
        api.client().getPermissions().blockingFirst()
        assertEquals("saved-key" to "saved-secret", savedCredentials())
        assertEquals(emptyList<Pair<String, String>>(), authenticated)
        assertEquals(emptyList<APIClientV1?>(), switched)
        assertSignedWith(requests[0], "submitted-key", "submitted-secret")
        assertSignedWith(requests[1], "saved-key", "saved-secret")
    }

    private fun savedCredentials() = preference.read().let { it.key to it.secret }

    private fun assertSignedWith(request: Request, key: String, secret: String) {
        assertEquals(key, request.header("Access-Key"))
        val path = request.url.encodedPath + (request.url.query?.let { "?$it" } ?: "")
        val signed = request.header("Access-Timestamp") + request.method + path
        assertEquals(signed.toHmacSHA256(secret), request.header("Access-Sign"))
    }
}
