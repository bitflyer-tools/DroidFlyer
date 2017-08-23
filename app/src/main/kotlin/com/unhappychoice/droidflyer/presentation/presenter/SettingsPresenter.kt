package com.unhappychoice.droidflyer.presentation.presenter

import android.util.Log
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import com.unhappychoice.norimaki.extension.subscribeNext
import mortar.MortarScope
import mortar.ViewPresenter

class SettingsPresenter(val activity: MainActivity) : ViewPresenter<SettingsView>() {
    val token: Variable<String> = Variable("")
    val secret: Variable<String> = Variable("")

    override fun onEnterScope(scope: MortarScope?) {
        super.onEnterScope(scope)
        token.value = APITokenPreference(activity.applicationContext).token
        secret.value = APITokenPreference(activity.applicationContext).secret
    }

    fun saveToken() {
        APITokenPreference(activity).token = token.value
        APITokenPreference(activity).secret = secret.value
    }
}
