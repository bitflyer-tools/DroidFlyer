package com.unhappychoice.droidflyer.di

import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.presentation.presenter.*
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun activityModule(activity: MainActivity) = DI.Module("activity") {
    bindProvider<MainActivity> { activity }
    bindSingleton<SettingsPresenter> { SettingsPresenter(instance(), instance(), instance(), realtimeClient = instance()) }
    bindSingleton<ChartPresenter> { ChartPresenter() }
    bindSingleton<OrderPresenter> { OrderPresenter(instance()) }
    bindSingleton<MarketOrderPresenter> { MarketOrderPresenter(instance(), instance()) }
    bindSingleton<LimitOrderPresenter> { LimitOrderPresenter(instance()) }
    bindSingleton<OrdersPresenter> { OrdersPresenter(instance()) }
}
