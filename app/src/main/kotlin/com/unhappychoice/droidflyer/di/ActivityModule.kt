package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.*
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.presentation.presenter.*

fun activityModule(activity: MainActivity) = Kodein.Module {
    bind<MainActivity>() with provider { activity }
    bind<RealtimeClient>() with singleton { RealtimeClient(instance(), instance()) }
    bind<CurrentStatusService>() with eagerSingleton { CurrentStatusService(instance(), instance()) }
    bind<SettingsPresenter>() with singleton { SettingsPresenter(instance(), instance(), instance()) }
    bind<ChartPresenter>() with singleton { ChartPresenter() }
    bind<OrderPresenter>() with singleton { OrderPresenter(instance()) }
    bind<MarketOrderPresenter>() with singleton { MarketOrderPresenter(instance(), instance()) }
    bind<LimitOrderPresenter>() with singleton { LimitOrderPresenter(instance()) }
    bind<OrdersPresenter>() with singleton { OrdersPresenter(instance(), instance()) }
}
