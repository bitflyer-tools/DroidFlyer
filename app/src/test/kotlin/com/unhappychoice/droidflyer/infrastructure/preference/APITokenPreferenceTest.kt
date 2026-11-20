package com.unhappychoice.droidflyer.infrastructure.preference

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStoreException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class APITokenPreferenceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val preferences by lazy { context.getSharedPreferences("api_token", Context.MODE_PRIVATE) }
    private val credentials = APICredentials("dummy-key-42", "dummy-secret-雪")
    private val preferenceFile = File(context.applicationInfo.dataDir, "shared_prefs/api_token.xml")

    @Test
    fun freshSaveEncryptsBothValuesAndCanBeReadByAnotherInstance() {
        val store = testAPITokenPreference(context)
        store.save(credentials)
        assertEncryptedOnDisk()
        assertCredentials(testAPITokenPreference(context).read())

        val firstSave = preferenceFile.readText()
        store.save(credentials)
        assertNotEquals(firstSave, preferenceFile.readText())
    }

    @Test
    fun readingLegacyCredentialsMigratesBothValues() {
        writeLegacyCredentials()

        assertCredentials(testAPITokenPreference(context).read())
        assertEncryptedOnDisk()
        assertCredentials(testAPITokenPreference(context).read())
    }

    @Test
    fun migrationCanRetryAfterEncryptionFails() {
        writeLegacyCredentials()
        val unavailable = APITokenPreference(context, APICredentialCipher {
            throw KeyStoreException("Key unavailable")
        })

        assertCredentials(unavailable.read())
        assertEquals(credentials.key, preferences.getString("key", null))
        assertEquals(credentials.secret, preferences.getString("secret", null))

        assertCredentials(testAPITokenPreference(context).read())
        assertEncryptedOnDisk()
    }

    @Test
    fun migrationCanRetryAfterCommitThrows() {
        writeLegacyCredentials()
        val storage = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = preferences.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?) = apply {
                                editor.putString(key, value)
                            }

                            override fun commit(): Boolean {
                                val saved = editor.commit()
                                if (!preferences.contains("key")) throw SecurityException("Write failed")
                                return saved
                            }
                        }
                    }
                }
        }

        assertCredentials(testAPITokenPreference(storage).read())
        assertEquals(credentials.key, preferences.getString("key", null))
        assertEquals(credentials.secret, preferences.getString("secret", null))
        val restored = preferenceFile.readText()
        assertTrue(restored.contains(credentials.key))
        assertTrue(restored.contains(credentials.secret))
        assertCredentials(testAPITokenPreference(context).read())
        assertEncryptedOnDisk()
    }

    @Test
    fun migrationResumesFromTheBackupAfterAnInterruptedWrite() {
        preferenceFile.parentFile!!.mkdirs()
        File("${preferenceFile.path}.bak").writeText(
            """<map><string name="key">${credentials.key}</string><string name="secret">${credentials.secret}</string></map>"""
        )
        preferenceFile.writeText("<map>")

        assertCredentials(testAPITokenPreference(context).read())
        assertEncryptedOnDisk()
    }

    private fun writeLegacyCredentials() {
        assertTrue(preferences.edit()
            .putString("key", credentials.key)
            .putString("secret", credentials.secret)
            .commit())
    }

    private fun assertEncryptedOnDisk() {
        assertFalse(preferences.contains("key"))
        assertFalse(preferences.contains("secret"))
        assertFalse(File("${preferenceFile.path}.bak").exists())
        val raw = preferenceFile.readText()
        assertFalse(raw.contains(credentials.key))
        assertFalse(raw.contains(credentials.secret))
    }

    private fun assertCredentials(actual: APICredentials) {
        assertEquals(credentials.key, actual.key)
        assertEquals(credentials.secret, actual.secret)
    }
}
