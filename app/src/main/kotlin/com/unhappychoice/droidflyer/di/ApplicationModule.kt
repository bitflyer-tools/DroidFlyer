package com.unhappychoice.droidflyer.di

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.CryptoCfdAccountPreference
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.style.Style
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun applicationModule(application: DroidFlyerApplication) = DI.Module("application") {
    bindSingleton<Gson> { createGson() }
    bindSingleton<CryptoCfdAccountPreference> { CryptoCfdAccountPreference(application) }
    bindSingleton<APITokenPreference> { APITokenPreference(application) }
    bindSingleton<APIClient> { APIClient(instance(), instance()) }
    bindSingleton<APIClientV1> { instance<APIClient>().client() }
    bindProvider<Style> { DefaultStyle }
}

private fun createGson() = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
