package com.unhappychoice.droidflyer

import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import io.reactivex.disposables.Disposable

/** Starts or stops [OrderFillNotificationService] as the linked account changes. */
internal class OrderFillNotificationController(
    private val preference: APITokenPreference,
    private val setRunning: (Boolean) -> Unit
) {
    fun start(): Disposable =
        preference.credentialsChanged
            .startWith(Unit)
            .map { preference.read().isConfigured }
            .distinctUntilChanged()
            .subscribeNext(setRunning)
}
