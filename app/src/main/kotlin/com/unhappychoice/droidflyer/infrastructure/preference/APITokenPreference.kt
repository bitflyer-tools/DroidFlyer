package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.IOException

class APITokenPreference internal constructor(
    context: Context,
    private val cipher: APICredentialCipher
) {
    constructor(context: Context) : this(context, APICredentialCipher())

    private val preferences by lazy { context.getSharedPreferences("api_token", Context.MODE_PRIVATE) }

    private val unlinks = PublishSubject.create<Unit>().toSerialized()
    internal val accountUnlinked = unlinks.hide()

    private val changes = PublishSubject.create<Unit>().toSerialized()

    /** Fires after a validated replacement or unlink. */
    internal val accountChanged = changes.hide()

    private val directSaves = PublishSubject.create<Unit>().toSerialized()
    internal val savedCredentialsChanged = accountChanged.mergeWith(directSaves)

    @Volatile
    internal var credentialsGeneration = 0L
        private set

    @Volatile
    internal var realtimeGeneration = 0L
        private set

    internal data class Snapshot(val credentials: APICredentials, val generation: Long)

    internal fun readSnapshot(): Snapshot = synchronized(lock) {
        Snapshot(read(), credentialsGeneration)
    }

    internal fun readRealtimeSnapshot(): Snapshot = synchronized(lock) {
        Snapshot(read(), realtimeGeneration)
    }

    fun clear(): Boolean {
        val cleared = synchronized(lock) {
            credentialsGeneration++
            realtimeGeneration++
            preferences.edit().remove(encryptedEntry).remove("key").remove("secret").commit()
        }
        unlinks.onNext(Unit)
        changes.onNext(Unit)
        return cleared
    }

    fun read(): APICredentials = synchronized(lock) {
        val values = preferences.all
        (values[encryptedEntry] as? String)?.let { return@synchronized readEncrypted(it) }

        val legacy = APICredentials(values["key"] as? String ?: "", values["secret"] as? String ?: "")
        if ("key" in values || "secret" in values) migrateLegacy(legacy)
        legacy
    }

    internal fun saveIfChanged(credentials: APICredentials, generation: Long): Boolean {
        val changed = synchronized(lock) {
            if (generation != credentialsGeneration) return@synchronized false

            val saved = read()
            if (saved.key == credentials.key && saved.secret == credentials.secret) return@synchronized false
            persistCredentials(credentials)
            credentialsGeneration++
            realtimeGeneration++
            true
        }
        if (changed) changes.onNext(Unit)
        return changed
    }

    fun save(credentials: APICredentials) {
        val changed = synchronized(lock) {
            val previous = preferences.all
            val saved = (previous[encryptedEntry] as? String)?.let(::readEncrypted)
                ?: APICredentials(previous["key"] as? String ?: "", previous["secret"] as? String ?: "")
            persistCredentials(credentials, previous)
            val changed = saved.key != credentials.key || saved.secret != credentials.secret
            if (changed) realtimeGeneration++
            changed
        }
        if (changed) directSaves.onNext(Unit)
    }

    private fun persistCredentials(credentials: APICredentials, previous: Map<String, *> = preferences.all) {
        val encrypted = cipher.encrypt(credentials)
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
            persistCredentials(credentials)
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
