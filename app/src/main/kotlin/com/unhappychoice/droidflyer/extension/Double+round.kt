package com.unhappychoice.droidflyer.extension

import java.math.BigDecimal
import java.math.RoundingMode

fun Double.round(x: Int): Double = BigDecimal(this).setScale(x, RoundingMode.HALF_UP).toDouble()