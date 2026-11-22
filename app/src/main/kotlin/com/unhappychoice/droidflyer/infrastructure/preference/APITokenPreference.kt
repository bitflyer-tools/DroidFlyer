package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import io.reactivex.subjects.PublishSubject
import java.io.IOException

class APITokenPreference internal constructor(
    context: Context,
    private val cipher: APICredentialCipher
) {
    constructor(context: Context) : this(context, APICredentialCipher())

    private val preferences by lazy { context.getSharedPreferences("api_token", Context.MODE_PRIVATE) }

    private val unlinks = PublishSubject.create<Unit>()
    internal val accountUnlinked = unlinks.hide()

    @Volatile
    internal var unlinkGeneration = 0L
        private set

    fun clear(): Boolean {
        val cleared = synchronized(lock) {
            unlinkGeneration++
            preferences.edit().remove(encryptedEntry).remove("key").remove("secret").commit()
        }
        unlinks.onNext(Unit)
        return cleared
    }

    fun read(): APICredentials = synchronized(lock) {
        val values = preferences.all
        (values[encryptedEntry] as? String)?.let { return@synchronized readEncrypted(it) }

        val legacy = APICredentials(values["key"] as? String ?: "", values["secret"] as? String ?: "")
        if ("key" in values || "secret" in values) migrateLegacy(legacy)
        legacy
    }

    internal fun saveIfChanged(credentials: APICredentials, generation: Long): Boolean = synchronized(lock) {
        if (generation != unlinkGeneration) return@synchronized false

        val saved = read()
        val changed = saved.key != credentials.key || saved.secret != credentials.secret
        if (changed) save(credentials)
        changed
    }

    fun save(credentials: APICredentials) = synchronized(lock) {
        val encrypted = cipher.encrypt(credentials)
        val previous = preferences.all
        try {
            persist(encrypted, null, null)
        } catch (failure: Exception) {
            // A failed commit can still update the in-memory preferences.
            try {
                persist(previous[encryptedEntry] as? String, previous["key"] as? String, previous["secret"] as? String)
            } catch (restoreFailure: Exception) {
                if (restoreFailure !== failure) failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    }

    private fun migrateLegacy(credentials: APICredentials) {
        try {
            save(credentials)
        } catch (_: Exception) {
            // Keep using the legacy pair and retry migration on the next read.
        }
    }

    private fun persist(encrypted: String?, key: String?, secret: String?) {
        val saved = preferences.edit()
            .putString(encryptedEntry, encrypted)
            .putString("key", key)
            .putString("secret", secret)
            .commit()
        if (!saved) throw IOException("Unable to save API credentials")
    }

    private fun readEncrypted(encrypted: String): APICredentials =
        try {
            cipher.decrypt(encrypted)
        } catch (_: Exception) {
            // Keep the ciphertext until a validated replacement is saved.
            APICredentials("", "")
        }

    private companion object {
        val lock = Any()
        const val encryptedEntry = "encrypted_credentials_v1"
    }
}
