package com.unhappychoice.droidflyer.infrastructure.preference

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class APICredentialCipher(private val key: (Boolean) -> SecretKey = ::keystoreKey) {
    fun encrypt(credentials: APICredentials): String {
        val payload = JsonArray().apply {
            add(credentials.key)
            add(credentials.secret)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == ivSize)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(payload), Base64.NO_WRAP)
    }

    fun decrypt(encrypted: String): APICredentials {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes, 0, ivSize))
        val payload = cipher.doFinal(bytes, ivSize, bytes.size - ivSize)
        val pair = JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonArray
        return APICredentials(pair[0].asString, pair[1].asString)
    }

    private companion object {
        const val transformation = "AES/GCM/NoPadding"
        const val ivSize = 12
        const val provider = "AndroidKeyStore"
        const val alias = "droidflyer_api_credentials_v1"

        fun keystoreKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance(provider).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            check(create) { "API credential encryption key is missing" }

            val spec = KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider).run {
                init(spec)
                generateKey()
            }
        }
    }
}
