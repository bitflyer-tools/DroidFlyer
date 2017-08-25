package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.provider
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.presenter.OrderPresenter
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter

fun activityModule(activity: MainActivity) = Kodein.Module {
    bind<SettingsPresenter>() with provider { SettingsPresenter(instance()) }
    bind<ChartPresenter>() with provider { ChartPresenter() }
    bind<OrderPresenter>() with provider { OrderPresenter(instance(), instance()) }
}