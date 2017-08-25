package com.unhappychoice.droidflyer.presentation.presenter

import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import com.unhappychoice.norimaki.extension.subscribeNext
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class SettingsPresenter(val preference: APITokenPreference) : ViewPresenter<SettingsView>() {
    val key: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
        key.value = preference.key
        secret.value = preference.secret

        key.asObservable().subscribeNext { preference.key = key.value }.addTo(bag)
        secret.asObservable().subscribeNext { preference.secret = secret.value }.addTo(bag)
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }
}
