package com.unhappychoice.droidflyer.di

import android.os.SystemClock
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.StopSubmissionPreference
import com.unhappychoice.droidflyer.presentation.presenter.*
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.eagerSingleton
import org.kodein.di.instance

fun activityModule(activity: MainActivity) = DI.Module("activity") {
    bindProvider<MainActivity> { activity }
    bindSingleton<StopSubmissionPreference> { StopSubmissionPreference(activity.applicationContext) }
    bindSingleton<RealtimeClient> { RealtimeClient(instance(), instance()) }
    bind<CurrentStatusService> {
        eagerSingleton {
            CurrentStatusService(
                instance(), instance(), null,
                maximumOrderSize = instance<CryptoCfdAccountPreference>().maximumOrderSize
            )
        }
    }
    bindSingleton<SettingsPresenter> {
        val notifications = (activity.application as DroidFlyerApplication).orderFillNotifications
        SettingsPresenter(
            instance(), instance(),
            accountPreference = instance(),
            requestNotificationPermission = activity::requestNotificationPermission,
            monitoring = notifications.running,
            toggleMonitoring = {
                if (notifications.running.value) notifications.stopMonitoring() else notifications.startMonitoring()
            }
        )
    }
    bindSingleton<ChartPresenter> {
        ChartPresenter(SystemClock::elapsedRealtimeNanos, instance<APIClientV1>()::getExecutionPrices)
    }
    bindSingleton<OrderPresenter> { OrderPresenter(instance()) }
    bindSingleton<MarketOrderPresenter> {
        MarketOrderPresenter(instance(), accountPreference = instance(), activity.resources, activity::showSnackbar)
    }
    bindSingleton<LimitOrderPresenter> {
        LimitOrderPresenter(instance<CurrentStatusService>(), accountPreference = instance(), activity.resources, activity::showSnackbar)
    }
    bindSingleton<OrdersPresenter> { OrdersPresenter(instance()) }
}

internal fun loadTradingAccount(
    api: APIClient,
    stopSubmissions: StopSubmissionPreference,
    snapshot: APITokenPreference.Snapshot
): Pair<APIClientV1, StopSubmissionGuard>? {
    return api.privateStatusClient(snapshot)?.let { client ->
        client to stopSubmissions.forApiKey(snapshot.credentials.key)
    }
}
