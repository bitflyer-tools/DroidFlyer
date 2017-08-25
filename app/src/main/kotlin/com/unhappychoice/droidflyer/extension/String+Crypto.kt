package com.unhappychoice.droidflyer.extension

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun String.toHmacSHA256(secret: String): String =
    Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        .doFinal(toByteArray())
        .toHexString()

private fun ByteArray.toHexString(): String {
    val builder = StringBuilder()
    this
        .map { String.format("%02x", it) }
        .forEach { builder.append(it) }
    return builder.toString()
}