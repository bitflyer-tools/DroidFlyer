package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.*
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.style.Style

fun applicationModule(application: DroidFlyerApplication) = Kodein.Module {
    bind<Gson>() with singleton { createGson() }
    bind<APIClientV1>() with provider { APIClient(instance(), instance("API_KEY"), instance("API_SECRET")).client() }
    bind<RealtimeClient>() with singleton { RealtimeClient(instance()) }

    bind<Style>() with provider { DefaultStyle }

    bind<APITokenPreference>() with provider { APITokenPreference(application) }
    bind<String>("API_KEY") with provider { APITokenPreference(application).key }
    bind<String>("API_SECRET") with provider { APITokenPreference(application).secret }
}

private fun createGson() = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()