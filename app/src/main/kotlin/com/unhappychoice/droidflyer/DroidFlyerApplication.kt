package com.unhappychoice.droidflyer

import android.app.Application
import mortar.MortarScope

class DroidFlyerApplication : Application() {
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
