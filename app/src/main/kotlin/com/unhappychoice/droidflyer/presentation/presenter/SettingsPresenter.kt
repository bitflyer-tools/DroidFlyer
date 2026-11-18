package com.unhappychoice.droidflyer.presentation.presenter

import com.github.unhappychoice.rxsnackbar.subscribeNextWithSnackBar
import com.github.unhappychoice.rxsnackbar.withErrorSnackBar
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.subscribeOnIoObserveOnUI
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference.Credentials
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import mortar.MortarScope
import mortar.ViewPresenter

class SettingsPresenter(
    val api: APIClient,
    val preference: APITokenPreference,
    val realtimeClient: RealtimeClient,
    val currentStatusService: CurrentStatusService
) : ViewPresenter<SettingsView>() {
    val key: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")
    private val bag = CompositeDisposable()

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
        val token = preference.credentials()
        key.value = token.key
        secret.value = token.secret
    }

    override fun onExitScope() {
        bag.clear()
        super.onExitScope()
    }

    fun save() {
        bag.clear()
        val candidate = Credentials(key.value, secret.value)
        val candidateClient = api.client(candidate)
        candidateClient.getBalance()
            .subscribeOnIoObserveOnUI()
            .doOnNext {
                if (!candidate.matches(preference.credentials())) {
                    preference.save(candidate)
                    currentStatusService.privateState.switchAccount(candidateClient)
                }
                realtimeClient.authenticate()
            }
            .withErrorSnackBar(view, view.context.getString(R.string.api_key_not_saved))
            .subscribeNextWithSnackBar(view, view.context.getString(R.string.api_key_saved))
            .addTo(bag)
    }
}
