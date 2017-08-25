package com.unhappychoice.droidflyer.presentation.style

import android.graphics.Color

interface Style {
    val buyColor: Int
    val sellColor: Int
    val primaryColor: Int
    val darkerPrimaryColor: Int
    val accentColor: Int
    val darkerAccentColor: Int

    val greenColor: Int
        get() = Color.rgb(12, 184, 37)
    val redColor: Int
        get() = Color.rgb(232, 0, 21)
}