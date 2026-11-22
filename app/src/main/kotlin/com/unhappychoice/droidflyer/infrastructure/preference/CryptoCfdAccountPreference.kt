package com.unhappychoice.droidflyer.infrastructure.preference

import android.content.Context
import com.unhappychoice.droidflyer.domain.service.CryptoCfdOrderSize
import com.unhappychoice.droidflyer.extension.Variable

class CryptoCfdAccountPreference(context: Context) {
    private val preferences = context.getSharedPreferences("crypto_cfd", Context.MODE_PRIVATE)

    var isCorporate: Boolean
        get() = preferences.getBoolean("corporate_account", false)
        set(value) {
            preferences.edit().putBoolean("corporate_account", value).apply()
            maximumOrderSize.value = maximumFor(value)
        }

    internal val maximumOrderSize = Variable(maximumFor(isCorporate))

    private fun maximumFor(corporate: Boolean): Double =
        if (corporate) CryptoCfdOrderSize.CORPORATE_MAXIMUM else CryptoCfdOrderSize.INDIVIDUAL_MAXIMUM
}
