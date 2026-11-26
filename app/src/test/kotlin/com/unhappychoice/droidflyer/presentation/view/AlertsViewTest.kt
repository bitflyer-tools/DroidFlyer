package com.unhappychoice.droidflyer.presentation.view

import android.app.Activity
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.AlertsViewBinding
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import com.unhappychoice.droidflyer.presentation.presenter.AlertsPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bindInstance
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertsViewTest {
    private val preference = PriceAlertPreference(RuntimeEnvironment.getApplication())
    private var monitoringStarts = 0
    private val presenter = AlertsPresenter(preference) { monitoringStarts++ }

    @Test
    fun addingAnAlertShowsItInTheListAndStartsMonitoring() {
        val alerts = open()

        alerts.alertPriceTextView.setText("12000000")
        alerts.addAlertButton.performClick()

        assertEquals(1, monitoringStarts)
        assertEquals(1, alerts.alertList.adapter?.itemCount)
        assertEquals("", alerts.alertPriceTextView.text.toString())
        assertFalse(alerts.noAlertsTextView.isVisible)
    }

    @Test
    fun blankInputIsIgnored() {
        val alerts = open()

        alerts.addAlertButton.performClick()

        assertEquals(0, monitoringStarts)
        assertEquals(0, alerts.alertList.adapter?.itemCount)
        assertTrue(alerts.noAlertsTextView.isVisible)
    }

    @Test
    fun removingAnAlertClearsItFromTheListAndStorage() {
        val alerts = open()
        alerts.alertPriceTextView.setText("12000000")
        alerts.addAlertButton.performClick()

        alerts.alertList.bindFirstRow().findViewById<View>(R.id.removeAlertButton).performClick()

        assertEquals(0, alerts.alertList.adapter?.itemCount)
        assertEquals(emptyList<Any>(), preference.read())
    }

    @Test
    fun reachingTheLimitShowsASnackbarAndKeepsExistingAlerts() {
        // The Material queue can retain callbacks from activities closed by earlier tests.
        ReflectionHelpers.setStaticField(
            Class.forName("com.google.android.material.snackbar.SnackbarManager"), "snackbarManager", null
        )
        val host = Robolectric.setupActivity(Activity::class.java)
        host.setTheme(R.style.AppTheme)
        val binding = AlertsViewBinding.inflate(host.layoutInflater)
        binding.root.inject(DI { bindInstance { AlertsPresenter(preference) { monitoringStarts++ } } })
        host.setContentView(binding.root)
        repeat(PriceAlertPreference.MAX_ALERTS) {
            binding.alertPriceTextView.setText((it + 1).toString())
            binding.addAlertButton.performClick()
        }

        binding.alertPriceTextView.setText("999")
        binding.addAlertButton.performClick()

        val message = host.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        assertEquals(
            host.getString(R.string.alert_limit_reached, PriceAlertPreference.MAX_ALERTS),
            message?.text?.toString()
        )
        assertEquals(PriceAlertPreference.MAX_ALERTS, binding.alertList.adapter?.itemCount)
    }

    private fun open(): AlertsViewBinding {
        val host = Robolectric.setupActivity(Activity::class.java)
        host.setTheme(R.style.AppTheme)
        val binding = AlertsViewBinding.inflate(host.layoutInflater)
        binding.root.inject(DI { bindInstance { presenter } })
        host.setContentView(binding.root)
        return binding
    }

    @Suppress("UNCHECKED_CAST")
    private fun RecyclerView.bindFirstRow(): View {
        val alerts = requireNotNull(adapter) as RecyclerView.Adapter<RecyclerView.ViewHolder>
        return alerts.createViewHolder(this, alerts.getItemViewType(0)).also {
            alerts.bindViewHolder(it, 0)
        }.itemView
    }
}
