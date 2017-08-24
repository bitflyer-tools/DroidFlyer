package com.unhappychoice.droidflyer.di

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.instance
import com.github.salomonbrys.kodein.singleton
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient

fun applicationModule() = Kodein.Module {
    bind<Gson>() with singleton { createGson() }
    bind<RealtimeClient>() with singleton { RealtimeClient(instance()) }
}

private fun createGson() = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()