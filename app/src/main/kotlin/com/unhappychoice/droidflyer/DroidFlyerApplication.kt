package com.unhappychoice.droidflyer

import android.support.multidex.MultiDexApplication
import com.squareup.leakcanary.LeakCanary
import mortar.MortarScope

class DroidFlyerApplication : MultiDexApplication() {
    private val scope by lazy {
        MortarScope.buildRootScope().build("root_scope")
    }

    override fun getSystemService(name: String?): Any? {
        return when (scope.hasService(name)) {
            true -> scope.getService(name)
            false -> super.getSystemService(name)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (LeakCanary.isInAnalyzerProcess(this)) return
        LeakCanary.install(this)
    }
}
