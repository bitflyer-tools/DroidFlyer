package com.unhappychoice.droidflyer.di

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.style.Style
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.eagerSingleton
import org.kodein.di.instance

fun applicationModule(application: DroidFlyerApplication) = DI.Module("application") {
    import(restModule(application))
    bindSingleton<RealtimeClient> { RealtimeClient(instance(), instance()) }

    bind<CurrentStatusService> {
        eagerSingleton {
            val credentials = instance<APITokenPreference>().read()
            CurrentStatusService(instance(), instance(), instance<APIClient>().client(credentials))
        }
    }

    bindProvider<Style> { DefaultStyle }
}

internal fun restModule(application: DroidFlyerApplication) = DI.Module("rest") {
    bindSingleton<Gson> { createGson() }
    bindSingleton<APITokenPreference> { APITokenPreference(application) }
    bindSingleton<APIClient> { APIClient(instance(), instance()) }
    bindSingleton<APIClientV1> { instance<APIClient>().client() }
}

private fun createGson() = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
