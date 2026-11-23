package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.snackbar.Snackbar
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

        val credentialLoad = SerialDisposable()
        credentialLoad.addTo(bag)
        presenter.accountUnlinked.startWith(Unit)
            .subscribeNext {
                setCredentialInputsEnabled(false)
                credentialLoad.set(presenter.loadCredentials {
                    binding.apiTokenTextView.setText(presenter.key.value)
                    binding.apiSecretTextView.setText(presenter.secret.value)
                    setCredentialInputsEnabled(true)
                })
            }
            .addTo(bag)

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
                val message = if (presenter.unlink()) R.string.account_unlinked else R.string.account_unlink_failed
                Snackbar.make(this, message, Snackbar.LENGTH_SHORT).show()
            }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        binding.corporateAccount.setOnCheckedChangeListener(null)
        presenter.dropView(this)
        super.onDetachedFromWindow()
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
    }
}
