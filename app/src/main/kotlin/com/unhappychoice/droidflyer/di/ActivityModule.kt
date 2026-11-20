package com.unhappychoice.droidflyer.di

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
            CurrentStatusService(instance(), instance(), instance<APIClient>().client(credentials))
        }
    }
    bindSingleton<SettingsPresenter> { SettingsPresenter(instance(), instance(), instance(), realtimeClient = instance()) }
    bindSingleton<ChartPresenter> { ChartPresenter(instance<APIClientV1>()::getExecutionPrices) }
    bindSingleton<OrderPresenter> { OrderPresenter(instance()) }
    bindSingleton<MarketOrderPresenter> { MarketOrderPresenter(instance()) }
    bindSingleton<LimitOrderPresenter> { LimitOrderPresenter(instance<CurrentStatusService>().privateState) }
    bindSingleton<OrdersPresenter> { OrdersPresenter(instance()) }
}
