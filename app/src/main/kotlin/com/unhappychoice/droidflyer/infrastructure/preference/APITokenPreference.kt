package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import jp.takuji31.koreference.KoreferenceModel
import jp.takuji31.koreference.bulk
import jp.takuji31.koreference.stringPreference

class APITokenPreference(context: Context) : KoreferenceModel(context, name = "api_token") {
    @Synchronized
    fun credentials() = Credentials(key, secret)

    @Synchronized
    fun save(credentials: Credentials) = bulk {
        key = credentials.key
        secret = credentials.secret
    }

    private var secret: String by stringPreference("")
    private var key: String by stringPreference("")

    class Credentials(val key: String, val secret: String) {
        fun matches(other: Credentials) = key == other.key && secret == other.secret
        fun isConfigured() = key.isNotEmpty() && secret.isNotEmpty()
    }
}
