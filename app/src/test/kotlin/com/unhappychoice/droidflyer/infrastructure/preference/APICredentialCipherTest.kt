package com.unhappychoice.droidflyer.infrastructure.preference

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class APICredentialCipherTest {
    private val cipher = APICredentialCipher { testCredentialKey }

    @Test
    fun decryptReturnsTheCredentialsThatWereEncrypted() {
        val credentials = APICredentials("dummy-key-42", "dummy-secret-雪")

        val decrypted = cipher.decrypt(cipher.encrypt(credentials))

        assertEquals(credentials.key, decrypted.key)
        assertEquals(credentials.secret, decrypted.secret)
    }

    @Test
    fun encryptingTheSameCredentialsTwiceProducesDifferentCiphertext() {
        val credentials = APICredentials("dummy-key", "dummy-secret")

        assertNotEquals(cipher.encrypt(credentials), cipher.encrypt(credentials))
    }

    @Test
    fun decryptRejectsCiphertextThatWasTamperedWith() {
        val encrypted = cipher.encrypt(APICredentials("dummy-key", "dummy-secret"))
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        bytes[bytes.size - 1] = bytes[bytes.size - 1].inc()
        val tampered = Base64.encodeToString(bytes, Base64.NO_WRAP)

        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }

    @Test
    fun decryptFailsWhenTheKeyDoesNotMatchTheOneUsedToEncrypt() {
        val encrypted = APICredentialCipher { testCredentialKey }.encrypt(APICredentials("dummy-key", "dummy-secret"))
        val otherKey = SecretKeySpec(KeyGenerator.getInstance("AES").generateKey().encoded, "AES")

        assertThrows(GeneralSecurityException::class.java) {
            APICredentialCipher { otherKey }.decrypt(encrypted)
        }
    }
}
