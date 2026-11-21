package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.google.gson.Gson
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.SettingsViewBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.screen.SettingsScreen
import flow.State
import hu.akarnokd.rxjava3.bridge.RxJavaBridge.toV3Scheduler
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins
import io.reactivex.schedulers.Schedulers
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewTest {
    @Test
    fun reattachingSettingsRestoresFieldAndApplySubscriptionsExactlyOnce() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        RxAndroidPlugins.setMainThreadSchedulerHandler { toV3Scheduler(Schedulers.trampoline()) }
        try {
            Robolectric.buildActivity(SettingsHost::class.java).setup().use { controller ->
                val host = controller.get()
                val settings = host.openSettings()
                repeat(3) { cycle -> assertSettingsReattachment(host, settings, cycle) }
            }
        } finally {
            RxAndroidPlugins.reset()
            RxJavaPlugins.reset()
        }
    }

    private fun assertSettingsReattachment(host: SettingsHost, settings: SettingsViewBinding, cycle: Int) {
        val presenter = settings.root.presenter
        val parent = settings.root.parent as ViewGroup
        val previousKey = presenter.key.value
        val previousSecret = presenter.secret.value
        parent.removeView(settings.root)
        settings.apiTokenTextView.setText("detached-key")
        settings.apiSecretTextView.setText("detached-secret")
        settings.saveButton.performClick()
        assertEquals(previousKey, presenter.key.value)
        assertEquals(previousSecret, presenter.secret.value)
        assertEquals(cycle, host.applyRequests)
        parent.addView(settings.root)

        val key = "key-$cycle"
        val secret = "secret-$cycle"
        val keys = presenter.key.asObservable().skip(1).test()
        val secrets = presenter.secret.asObservable().skip(1).test()
        settings.apiTokenTextView.setText(key)
        settings.apiSecretTextView.setText(secret)
        keys.assertValues(key).dispose()
        secrets.assertValues(secret).dispose()

        settings.saveButton.performClick()
        assertEquals(cycle + 1, host.applyRequests)
        val saved = testAPITokenPreference(host).read()
        assertEquals(key, saved.key)
        assertEquals(secret, saved.secret)
    }

    @Test
    fun credentialsAreOmittedFromActivityAndFlowStateAndReloadedOnReopen() {
        Robolectric.buildActivity(SettingsHost::class.java).setup().use { controller ->
            val activity = controller.get()
            val settings = activity.openSettings()
            val flowState = State.empty(SettingsScreen())
            assertEquals("stored-key", settings.apiTokenTextView.text.toString())
            assertEquals("stored-secret", settings.apiSecretTextView.text.toString())
            assertCredentialsOmitted(settings, Bundle().also { controller.saveInstanceState(it) }, flowState)

            settings.apiTokenTextView.setText("draft-key")
            settings.apiSecretTextView.setText("draft-secret")
            assertCredentialsOmitted(settings, Bundle().also { controller.saveInstanceState(it) }, flowState)

            activity.setContentView(LinearLayout(activity))
            testAPITokenPreference(activity).save(APICredentials("updated-key", "updated-secret"))
            val reopened = activity.openSettings()
            flowState.restore(reopened.root)
            assertEquals("updated-key", reopened.apiTokenTextView.text.toString())
            assertEquals("updated-secret", reopened.apiSecretTextView.text.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun assertCredentialsOmitted(settings: SettingsViewBinding, activityState: Bundle, flowState: State) {
        val hierarchy = activityState.getBundle("android:viewHierarchyState")
            ?.getSparseParcelableArray<Parcelable>("android:views")
        assertNotNull(hierarchy?.get(R.id.saveButton))
        assertNull(hierarchy?.get(R.id.apiTokenTextView))
        assertNull(hierarchy?.get(R.id.apiSecretTextView))

        val probe = LinearLayout(settings.root.context).apply { id = settings.root.id }
        val key = EditText(probe.context).apply { id = R.id.apiTokenTextView }
        val secret = EditText(probe.context).apply { id = R.id.apiSecretTextView }
        probe.addView(key)
        probe.addView(secret)
        flowState.save(settings.root)
        flowState.restore(probe)
        assertEquals("", key.text.toString())
        assertEquals("", secret.text.toString())
    }

    class SettingsHost : Activity() {
        var applyRequests = 0
            private set

        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("settings_test")
        }
        private val di by lazy {
            val preference = testAPITokenPreference(this).apply {
                save(APICredentials("stored-key", "stored-secret"))
            }
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                applyRequests++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("Test response")
                    .body("[]".toResponseBody())
                    .build()
            }.build()
            val presenter = SettingsPresenter(APIClient(Gson(), preference, transport), preference, switchAccount = {}) {}
            DI { bindInstance { presenter } }
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        fun openSettings(): SettingsViewBinding = SettingsViewBinding.inflate(layoutInflater).also {
            it.root.inject(di)
            setContentView(it.root)
        }
    }
}
