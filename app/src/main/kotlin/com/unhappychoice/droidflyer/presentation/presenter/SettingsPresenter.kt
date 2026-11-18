package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.subscribeNextWithSnackBar
import com.github.unhappychoice.rxsnackbar.withErrorSnackBar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class SettingsPresenter internal constructor(
    private val api: APIClientV1,
    private val preference: APITokenPreference,
    private val authenticate: () -> Unit
) : ViewPresenter<SettingsView>() {
    constructor(api: APIClientV1, preference: APITokenPreference, realtimeClient: RealtimeClient) :
        this(api, preference, realtimeClient::authenticate)

    val key: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
        val credentials = preference.read()
        key.value = credentials.key
        secret.value = credentials.secret
    }

    override fun onExitScope() {
        bag.dispose()
        super.onExitScope()
    }

    fun save() {
        saveCredentials()
            .withErrorSnackBar(view, view.context.getString(R.string.api_key_not_saved))
            .subscribeNextWithSnackBar(view, view.context.getString(R.string.api_key_saved))
            .addTo(bag)
    }

    internal fun saveCredentials(): Observable<List<Map<String, Any>>> {
        val submitted = APICredentials(key.value, secret.value)
        return api.getBalance(submitted)
            .subscribeOnIoObserveOnUI()
            .doOnNext {
                preference.save(submitted)
                authenticate()
            }
    }
}
