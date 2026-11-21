package com.unhappychoice.droidflyer.domain.service

internal object CryptoCfdOrderSize {
    const val MINIMUM = 0.001

    fun isValid(quantity: Double): Boolean = quantity.isFinite() && quantity >= MINIMUM
}
