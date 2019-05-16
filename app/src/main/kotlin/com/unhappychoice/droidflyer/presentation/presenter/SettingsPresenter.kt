package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.subscribeNextWithSnackBar
import com.github.unhappychoice.rxsnackbar.withErrorSnackBar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class SettingsPresenter(
    val api: APIClientV1,
    val preference: APITokenPreference
) : ViewPresenter<SettingsView>() {
    val key: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
        key.value = preference.key
        secret.value = preference.secret
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun save() {
        preference.key = key.value
        preference.secret = secret.value

        api.getBalance()
            .subscribeOnIoObserveOnUI()
            .withErrorSnackBar(view, view.context.getString(R.string.api_key_not_saved))
            .subscribeNextWithSnackBar(view, view.context.getString(R.string.api_key_saved))
            .addTo(bag)
    }
}
