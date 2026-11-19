package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import io.reactivex.subjects.PublishSubject

class APITokenPreference(context: Context) {
    private val preferences = context.getSharedPreferences("api_token", Context.MODE_PRIVATE)

    private val unlinks = PublishSubject.create<Unit>()
    internal val accountUnlinked = unlinks.hide()

    @Volatile
    internal var unlinkGeneration = 0L
        private set

    fun clear(): Boolean {
        unlinkGeneration++
        val cleared = preferences.edit().remove("key").remove("secret").commit()
        unlinks.onNext(Unit)
        return cleared
    }

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
