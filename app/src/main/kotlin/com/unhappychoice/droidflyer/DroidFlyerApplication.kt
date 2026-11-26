package com.unhappychoice.droidflyer

import android.app.Application
import com.unhappychoice.droidflyer.di.applicationModule
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import mortar.MortarScope
import org.kodein.di.DI

class DroidFlyerApplication : Application() {
    internal val tokenPreference by lazy { APITokenPreference(this) }

    internal val module by lazy { DI { import(applicationModule(this@DroidFlyerApplication)) } }

    private val scope by lazy {
        MortarScope.buildRootScope().build("root_scope")
    }

    override fun onCreate() {
        super.onCreate()
        OrderFillNotificationController(tokenPreference) { linked ->
            if (linked) OrderFillNotificationService.start(this) else OrderFillNotificationService.stop(this)
        }.start()
    }

    override fun getSystemService(name: String): Any? {
        return when (scope.hasService(name)) {
            true -> scope.getService(name)
            false -> super.getSystemService(name)
        }
    }
}
