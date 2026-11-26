package com.unhappychoice.droidflyer.domain.service

import java.math.BigDecimal

internal object CryptoCfdOrderSize {
    const val MINIMUM = 0.001

    const val INDIVIDUAL_MAXIMUM = 10.0
    const val CORPORATE_MAXIMUM = 40.0

    fun isValid(quantity: Double, maximum: Double): Boolean = meetsMinimum(quantity) && quantity <= maximum

    fun isValid(quantity: BigDecimal, maximum: Double): Boolean =
        meetsMinimum(quantity) && quantity <= maximum.toBigDecimal()

    fun meetsMinimum(quantity: BigDecimal): Boolean = quantity >= MINIMUM.toBigDecimal()

    fun meetsMinimum(quantity: Double): Boolean = quantity.isFinite() && quantity >= MINIMUM
}
