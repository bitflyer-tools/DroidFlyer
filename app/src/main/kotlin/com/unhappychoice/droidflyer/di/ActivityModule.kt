package com.unhappychoice.droidflyer.di

import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
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
    bind<CurrentStatusService> { eagerSingleton { CurrentStatusService(instance(), instance()) } }
    bindSingleton<SettingsPresenter> { SettingsPresenter(instance(), instance(), realtimeClient = instance()) }
    bindSingleton<ChartPresenter> { ChartPresenter(instance<APIClientV1>()::getExecutionPrices) }
    bindSingleton<OrderPresenter> { OrderPresenter(instance()) }
    bindSingleton<MarketOrderPresenter> { MarketOrderPresenter(instance(), instance()) }
    bindSingleton<LimitOrderPresenter> { LimitOrderPresenter(instance()) }
    bindSingleton<OrdersPresenter> { OrdersPresenter(instance(), instance()) }
}
