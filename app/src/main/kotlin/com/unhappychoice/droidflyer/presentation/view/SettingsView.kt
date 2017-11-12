package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.settings_view.view.*


class SettingsView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: SettingsPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        setupStyle()

        apiTokenTextView.setText(presenter.key.value)
        apiSecretTextView.setText(presenter.secret.value)

        apiTokenTextView.textChanges()
            .subscribeNext {
                presenter.key.value = it.toString()
                setupStyle()
            }.addTo(bag)

        apiSecretTextView.textChanges()
            .subscribeNext {
                presenter.secret.value = it.toString()
                setupStyle()
            }.addTo(bag)

        saveButton.clicks()
            .subscribeNext { presenter.save() }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }

    private fun setupStyle() {
        apiTokenTextView.setTextColor(DefaultStyle.accentColor)
        apiTokenTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        apiSecretTextView.setTextColor(DefaultStyle.accentColor)
        apiSecretTextView.setHintTextColor(DefaultStyle.darkerAccentColor)
        saveButton.setTextColor(DefaultStyle.accentColor)
        saveButton.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
    }
}

// 5f8vGDefZgLa2RUd2vfNzT