package com.unhappychoice.droidflyer.presentation.view

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.OrderFillNotificationService
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.databinding.SettingsViewBinding
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.droidflyer.presentation.view.core.clicks
import com.unhappychoice.droidflyer.presentation.view.core.textChanges
import io.reactivex.disposables.SerialDisposable
import io.reactivex.rxkotlin.addTo
import org.kodein.di.instance


class SettingsView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: SettingsPresenter by instance()
    private val binding by lazy { SettingsViewBinding.bind(this) }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding.apiTokenTextView.isSaveFromParentEnabled = false
        binding.apiSecretTextView.isSaveFromParentEnabled = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        presenter.accountLinked.asObservable()
            .subscribeNext { updateNotificationButtons(it) }
            .addTo(bag)
        presenter.monitoring.asObservable()
            .subscribeNext { updateNotificationButtons(presenter.accountLinked.value) }
            .addTo(bag)
        binding.enableNotificationsButton.clicks()
            .subscribeNext {
                if (binding.enableNotificationsButton.isVisible) onNotificationButtonClicked()
            }
            .addTo(bag)

        binding.notificationSettingsButton.clicks()
            .subscribeNext {
                if (binding.notificationSettingsButton.isVisible) openNotificationSettings()
            }
            .addTo(bag)

        val credentialLoad = SerialDisposable()
        credentialLoad.addTo(bag)
        setCredentialInputsEnabled(false)
        credentialLoad.set(presenter.loadCredentials {
            binding.apiTokenTextView.setText(presenter.key.value)
            binding.apiSecretTextView.setText(presenter.secret.value)
            setCredentialInputsEnabled(true)
        })

        binding.apiTokenTextView.textChanges()
            .subscribeNext {
                presenter.key.value = it.toString()
                setupStyle()
            }.addTo(bag)

        binding.apiSecretTextView.textChanges()
            .subscribeNext {
                presenter.secret.value = it.toString()
                setupStyle()
            }.addTo(bag)

        val accountPreference = presenter.accountPreference
        binding.corporateAccount.isChecked = accountPreference.isCorporate
        binding.corporateAccount.setOnCheckedChangeListener { _, checked -> accountPreference.isCorporate = checked }

        binding.saveButton.clicks()
            .subscribeNext { if (binding.saveButton.isEnabled) presenter.save() }
            .addTo(bag)

        binding.unlinkButton.clicks()
            .subscribeNext {
                setCredentialInputsEnabled(false)
                val cleared = presenter.unlink {
                    binding.apiTokenTextView.setText(presenter.key.value)
                    binding.apiSecretTextView.setText(presenter.secret.value)
                    setCredentialInputsEnabled(true)
                }
                val message = if (cleared) R.string.account_unlinked else R.string.account_unlink_failed
                Snackbar.make(this, message, Snackbar.LENGTH_SHORT).show()
            }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        binding.corporateAccount.setOnCheckedChangeListener(null)
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isAttachedToWindow) updateNotificationButtons(presenter.accountLinked.value)
    }

    private fun updateNotificationButtons(accountLinked: Boolean) {
        binding.enableNotificationsButton.isVisible = accountLinked || presenter.monitoring.value
        binding.notificationSettingsButton.isVisible =
            accountLinked && !presenter.monitoring.value && needsNotificationPermission()
        binding.enableNotificationsButton.setText(
            when {
                presenter.monitoring.value -> R.string.stop_order_monitoring
                !OrderFillNotificationService.notificationsAllowed(context) -> R.string.enable_order_notifications
                else -> R.string.start_order_monitoring
            }
        )
    }

    private fun onNotificationButtonClicked() {
        when {
            presenter.monitoring.value || OrderFillNotificationService.notificationsAllowed(context) ->
                presenter.toggleMonitoring()
            needsNotificationPermission() -> presenter.requestNotificationPermission()
            else -> openNotificationSettings()
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent)
    }

    private fun setCredentialInputsEnabled(enabled: Boolean) {
        binding.apiTokenTextView.isEnabled = enabled
        binding.apiSecretTextView.isEnabled = enabled
        binding.saveButton.isEnabled = enabled
    }

    private fun setupStyle() {
        binding.apiTokenTextView.setTextColor(DefaultStyle.accentColor)
        binding.apiTokenTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        binding.apiSecretTextView.setTextColor(DefaultStyle.accentColor)
        binding.apiSecretTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        binding.saveButton.setTextColor(DefaultStyle.accentColor)
        binding.saveButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.unlinkButton.setTextColor(DefaultStyle.accentColor)
        binding.unlinkButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.notificationSettingsButton.setTextColor(DefaultStyle.accentColor)
        binding.notificationSettingsButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.enableNotificationsButton.setTextColor(DefaultStyle.accentColor)
        binding.enableNotificationsButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
