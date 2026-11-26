package com.unhappychoice.droidflyer

import android.app.Application
import com.unhappychoice.droidflyer.infrastructure.notification.createPriceAlertNotificationChannel
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import com.unhappychoice.droidflyer.infrastructure.service.PriceAlertService
import mortar.MortarScope

class DroidFlyerApplication : Application() {
    internal val tokenPreference by lazy { APITokenPreference(this) }

    private val scope by lazy {
        MortarScope.buildRootScope().build("root_scope")
    }

    override fun onCreate() {
        super.onCreate()
        createPriceAlertNotificationChannel()
        if (PriceAlertPreference(this).read().isNotEmpty()) PriceAlertService.start(this)
    }

    override fun getSystemService(name: String): Any? {
        return when (scope.hasService(name)) {
            true -> scope.getService(name)
            false -> super.getSystemService(name)
        }
    }
}
