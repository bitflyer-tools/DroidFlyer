package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.*
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.infrastructure.bitflyer.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference

fun applicationModule(application: DroidFlyerApplication) = Kodein.Module {
    bind<Gson>() with singleton { createGson() }
    bind<APIClient>() with provider { APIClient(instance(), instance("API_KEY"), instance("API_SECRET"))}
    bind<RealtimeClient>() with singleton { RealtimeClient(instance()) }

    bind<APITokenPreference>() with provider { APITokenPreference(application) }
    bind<String>("API_KEY") with provider { APITokenPreference(application).key }
    bind<String>("API_SECRET") with provider { APITokenPreference(application).secret }
}

private fun createGson() = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()