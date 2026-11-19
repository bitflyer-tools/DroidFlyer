package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import javax.crypto.spec.SecretKeySpec

internal fun testAPITokenPreference(context: Context) =
    APITokenPreference(context, APICredentialCipher { testCredentialKey })

internal val testCredentialKey = SecretKeySpec(ByteArray(32), "AES")
