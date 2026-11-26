package com.unhappychoice.droidflyer.presentation.view

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AttributeSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.AlertsViewBinding
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.adapter.PriceAlertAdapter
import com.unhappychoice.droidflyer.presentation.presenter.AlertsPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance

class AlertsView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: AlertsPresenter by instance()
    private val binding by lazy { AlertsViewBinding.bind(this) }
    private val adapter by lazy { PriceAlertAdapter { presenter.removeAlert(it.id) } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)
        setupStyle()
        requestNotificationPermission()

        binding.alertList.layoutManager = LinearLayoutManager(context)
        binding.alertList.adapter = adapter

        presenter.alerts
            .subscribeNext(::renderAlerts)
            .addTo(bag)

        binding.addAlertButton.setOnClickListener { addAlert() }
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun addAlert() {
        val targetPrice = binding.alertPriceTextView.text.toString().toLongOrNull()
        if (targetPrice == null) return

        val direction = if (binding.alertDirectionAbove.isChecked) PriceAlertDirection.ABOVE else PriceAlertDirection.BELOW
        if (presenter.addAlert(targetPrice, direction)) {
            binding.alertPriceTextView.setText("")
        } else {
            Snackbar.make(this, context.getString(R.string.alert_limit_reached, presenter.maximumAlerts), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun renderAlerts(alerts: List<PriceAlert>) {
        adapter.update(alerts)
        binding.noAlertsTextView.isVisible = alerts.isEmpty()
        binding.alertList.isVisible = alerts.isNotEmpty()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = context as? Activity ?: return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }

    private fun setupStyle() {
        binding.alertPriceTextView.setTextColor(DefaultStyle.accentColor)
        binding.alertPriceTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        binding.alertDirectionAbove.setTextColor(DefaultStyle.accentColor)
        binding.alertDirectionBelow.setTextColor(DefaultStyle.accentColor)
        binding.addAlertButton.setTextColor(DefaultStyle.accentColor)
        binding.addAlertButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.noAlertsTextView.setTextColor(DefaultStyle.accentColor)
    }
}
