package com.unhappychoice.droidflyer.presentation.view

import android.content.Context
import android.util.AttributeSet
import com.github.salomonbrys.kodein.instance
import com.jakewharton.rxbinding2.widget.textChanges
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.view.core.BaseView
import com.unhappychoice.norimaki.extension.subscribeNext
import io.reactivex.rxkotlin.addTo
import kotlinx.android.synthetic.main.settings_view.view.*

class SettingsView(context: Context?, attr: AttributeSet?) : BaseView(context, attr) {
    val presenter: SettingsPresenter by instance()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        presenter.takeView(this)

        apiTokenTextView.setText(presenter.key.value)
        apiSecretTextView.setText(presenter.secret.value)

        apiTokenTextView.textChanges()
            .subscribeNext { presenter.key.value = it.toString() }
            .addTo(bag)

        apiSecretTextView.textChanges()
            .subscribeNext { presenter.secret.value = it.toString() }
            .addTo(bag)
    }

    override fun onDetachedFromWindow() {
        presenter.dropView(this)
        super.onDetachedFromWindow()
    }
}