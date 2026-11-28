package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers

/** Starts monitoring from the visible app and stops it when the account is unlinked. */
internal class OrderFillNotificationController(
    private val preference: APITokenPreference,
    private val setRunning: (Boolean) -> Unit
) {
    private var appResumed = false
    private var accountLinked = false

    fun start(): Disposable =
        preference.credentialsChanged
            .startWith(Unit)
            .observeOn(Schedulers.io())
            .map { preference.read().isConfigured }
            .observeOn(androidMainThreadScheduler())
            .filter { it != accountLinked }
            .subscribeNext { linked ->
                accountLinked = linked
                if (!linked || appResumed) setRunning(linked)
            }

    fun onAppResumed() {
        appResumed = true
        if (accountLinked) setRunning(true)
    }

    fun onAppPaused() {
        appResumed = false
    }
}
