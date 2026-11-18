package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context

class APITokenPreference(context: Context) {
    private val preferences = context.getSharedPreferences("api_token", Context.MODE_PRIVATE)

    fun read(): APICredentials {
        val values = preferences.all
        return APICredentials(values["key"] as? String ?: "", values["secret"] as? String ?: "")
    }

    fun save(credentials: APICredentials) {
        preferences.edit()
            .putString("key", credentials.key)
            .putString("secret", credentials.secret)
            .apply()
    }
}
