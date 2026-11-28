package com.unhappychoice.droidflyer

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import android.util.Log
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
                if (!linked) setRunning(false)
                else if (appResumed) startMonitoring()
            }

    fun onAppResumed() {
        appResumed = true
        if (accountLinked) startMonitoring()
    }

    fun onAppPaused() {
        appResumed = false
    }

    private fun startMonitoring() {
        try {
            setRunning(true)
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                error !is ForegroundServiceStartNotAllowedException
            ) throw error
            Log.w("OrderFillNotifications", "Service start denied. Will retry when the app resumes.", error)
        }
    }
}
