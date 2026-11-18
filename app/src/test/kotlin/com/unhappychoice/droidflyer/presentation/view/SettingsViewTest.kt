package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.os.Bundle
import android.os.Parcelable
import android.widget.EditText
import android.widget.LinearLayout
import com.google.gson.Gson
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.SettingsViewBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.screen.SettingsScreen
import flow.State
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewTest {
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
            APITokenPreference(activity).save(APICredentials("updated-key", "updated-secret"))
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
        private val scope by lazy {
            MortarScope.buildRootScope()
                .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
                .build("settings_test")
        }
        private val module by lazy {
            val preference = APITokenPreference(this).apply {
                save(APICredentials("stored-key", "stored-secret"))
            }
            val presenter = SettingsPresenter(APIClient(Gson(), preference).client(), preference) {}
            DI { bindSingleton<SettingsPresenter> { presenter } }
        }

        override fun getSystemService(name: String): Any? =
            if (scope.hasService(name)) scope.getService(name) else super.getSystemService(name)

        fun openSettings(): SettingsViewBinding = SettingsViewBinding.inflate(layoutInflater).also {
            it.root.inject(module)
            setContentView(it.root)
        }
    }
}
