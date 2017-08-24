package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.provider
import com.unhappychoice.droidflyer.MainActivity
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter

fun activityModule(activity: MainActivity) = Kodein.Module {
    bind<SettingsPresenter>() with provider { SettingsPresenter(activity) }
    bind<ChartPresenter>() with provider { ChartPresenter(activity) }
}