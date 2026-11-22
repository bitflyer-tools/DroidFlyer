package com.unhappychoice.droidflyer.di

import android.os.SystemClock
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.*
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.eagerSingleton
import org.kodein.di.instance

fun activityModule(activity: MainActivity) = DI.Module("activity") {
    bindProvider<MainActivity> { activity }
    bindSingleton<RealtimeClient> { RealtimeClient(instance(), instance()) }
    bind<CurrentStatusService> {
        eagerSingleton {
            val credentials = instance<APITokenPreference>().read()
            CurrentStatusService(instance(), instance(), instance<APIClient>().privateStatusClient(credentials))
        }
    }
    bindSingleton<SettingsPresenter> { SettingsPresenter(instance(), instance(), instance(), realtimeClient = instance()) }
    bindSingleton<ChartPresenter> {
        ChartPresenter(SystemClock::elapsedRealtimeNanos, instance<APIClientV1>()::getExecutionPrices)
    }
    bindSingleton<OrderPresenter> { OrderPresenter(instance()) }
    bindSingleton<MarketOrderPresenter> { MarketOrderPresenter(instance(), instance()) }
    bindSingleton<LimitOrderPresenter> { LimitOrderPresenter(instance(), activity.resources, activity::showSnackbar) }
    bindSingleton<OrdersPresenter> { OrdersPresenter(instance()) }
}
