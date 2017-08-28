package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.*
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter

fun activityModule(activity: MainActivity) = Kodein.Module {
    bind<MainActivity>() with provider { activity }
    bind<SettingsPresenter>() with singleton { SettingsPresenter(instance()) }
    bind<ChartPresenter>() with singleton { ChartPresenter() }
    bind<OrderPresenter>() with singleton { OrderPresenter(instance(), instance()) }
}