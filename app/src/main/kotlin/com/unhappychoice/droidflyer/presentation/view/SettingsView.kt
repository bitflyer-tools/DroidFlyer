package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.databinding.SettingsViewBinding
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo


class SettingsView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: SettingsPresenter by instance()
    private val binding by lazy { SettingsViewBinding.bind(this) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        binding.apiTokenTextView.setText(presenter.key.value)
        binding.apiSecretTextView.setText(presenter.secret.value)

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

        binding.saveButton.clicks()
            .subscribeNext { presenter.save() }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        binding.apiTokenTextView.setTextColor(DefaultStyle.accentColor)
        binding.apiTokenTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        binding.apiSecretTextView.setTextColor(DefaultStyle.accentColor)
        binding.apiSecretTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        binding.saveButton.setTextColor(DefaultStyle.accentColor)
        binding.saveButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}
