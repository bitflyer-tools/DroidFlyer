package com.unhappychoice.droidflyer

import android.app.Application
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import mortar.MortarScope

class DroidFlyerApplication : Application() {
    internal val tokenPreference by lazy { APITokenPreference(this) }

    private val scope by lazy {
        MortarScope.buildRootScope().build("root_scope")
    }

    override fun getSystemService(name: String): Any? {
        return when (scope.hasService(name)) {
            true -> scope.getService(name)
            false -> super.getSystemService(name)
        }
    }
}
