package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import java.io.IOException

class APITokenPreference internal constructor(
    context: Context,
    private val cipher: APICredentialCipher
) {
    constructor(context: Context) : this(context, APICredentialCipher())

    private val preferences = context.getSharedPreferences("api_token", Context.MODE_PRIVATE)

    fun read(): APICredentials = synchronized(lock) {
        val values = preferences.all
        (values[encryptedEntry] as? String)?.let { return@synchronized cipher.decrypt(it) }

        val legacy = APICredentials(values["key"] as? String ?: "", values["secret"] as? String ?: "")
        if ("key" in values || "secret" in values) save(legacy)
        legacy
    }

    fun save(credentials: APICredentials) = synchronized(lock) {
        val encrypted = cipher.encrypt(credentials)
        val previous = preferences.all
        // SharedPreferences commits the encrypted pair and the legacy removals in one file transaction.
        val saved = preferences.edit()
            .putString(encryptedEntry, encrypted)
            .remove("key")
            .remove("secret")
            .commit()
        if (!saved) {
            // A failed commit can still update the in-memory preferences.
            preferences.edit()
                .putString(encryptedEntry, previous[encryptedEntry] as? String)
                .putString("key", previous["key"] as? String)
                .putString("secret", previous["secret"] as? String)
                .commit()
            throw IOException("Unable to save API credentials")
        }
    }

    private companion object {
        val lock = Any()
        const val encryptedEntry = "encrypted_credentials_v1"
    }
}
