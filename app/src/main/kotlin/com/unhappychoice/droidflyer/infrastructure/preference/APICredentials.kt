package com.unhappychoice.droidflyer.infrastructure.preference

class APICredentials(val key: String, val secret: String) {
    val isComplete: Boolean get() = key.isNotEmpty() && secret.isNotEmpty()
}
