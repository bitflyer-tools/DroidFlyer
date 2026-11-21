package com.unhappychoice.droidflyer.infrastructure.preference

class APICredentials(val key: String, val secret: String) {
    val isConfigured: Boolean get() = key.isNotEmpty() && secret.isNotEmpty()
}
