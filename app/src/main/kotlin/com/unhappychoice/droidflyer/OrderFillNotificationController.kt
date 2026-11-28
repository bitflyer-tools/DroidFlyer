package com.unhappychoice.droidflyer

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build
import android.util.Log
import com.unhappychoice.droidflyer.extension.Variable
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.disposables.Disposable

internal class OrderFillNotificationController(
    private val preference: APITokenPreference,
    private val notificationsAllowed: () -> Boolean,
    private val setRunning: (Boolean) -> Unit
) {
    val running = Variable(false)

    fun observeUnlink(): Disposable = preference.accountUnlinked
        .observeOn(androidMainThreadScheduler())
        .subscribeNext { stopMonitoring() }

    fun startMonitoring() {
        if (running.value || !notificationsAllowed()) return
        try {
            setRunning(true)
            running.value = true
        } catch (error: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                error !is ForegroundServiceStartNotAllowedException
            ) throw error
            Log.w("OrderFillNotifications", "Service start denied.", error)
        }
    }

    fun stopMonitoring() {
        if (!running.value) return
        setRunning(false)
        running.value = false
    }

    fun stopIfNotificationsDisabled() {
        if (!notificationsAllowed()) stopMonitoring()
    }
}
