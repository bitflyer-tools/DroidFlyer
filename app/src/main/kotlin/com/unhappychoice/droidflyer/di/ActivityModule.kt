package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.*
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.presentation.presenter.*

fun activityModule(activity: MainActivity) = Kodein.Module {
    bind<MainActivity>() with provider { activity }
    bind<SettingsPresenter>() with singleton { SettingsPresenter(instance()) }
    bind<ChartPresenter>() with singleton { ChartPresenter() }
    bind<OrderPresenter>() with singleton { OrderPresenter(instance()) }
    bind<MarketOrderPresenter>() with singleton { MarketOrderPresenter(instance(), instance()) }
    bind<LimitOrderPresenter>() with singleton { LimitOrderPresenter(instance()) }
    bind<OrdersPresenter>() with singleton { OrdersPresenter(instance(), instance()) }
}
