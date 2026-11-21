package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import com.unhappychoice.droidflyer.domain.service.StopSubmissionGuard
import java.security.MessageDigest

internal class StopSubmissionPreference(context: Context) {
    private val preferences = context.getSharedPreferences("stop_submissions", Context.MODE_PRIVATE)

    fun forApiKey(apiKey: String): StopSubmissionGuard {
        val key = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return object : StopSubmissionGuard {
            override fun pending(): String? = synchronized(lock) {
                preferences.getString(key, null)
            }

            override fun reserve(id: String): Boolean = synchronized(lock) {
                if (pending() != null) return@synchronized false
                preferences.edit().putString(key, id).commit()
            }

            override fun release(id: String): Boolean = synchronized(lock) {
                if (pending() != id) return@synchronized false
                if (preferences.edit().remove(key).commit()) return@synchronized true
                // SharedPreferences updates its in-memory state even when a disk write fails.
                preferences.edit().putString(key, id).commit()
                false
            }
        }
    }

    private companion object {
        val lock = Any()
    }
}
