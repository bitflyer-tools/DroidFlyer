package com.unhappychoice.droidflyer

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.provider
import com.unhappychoice.droidflyer.presentation.core.GsonParceler
import com.unhappychoice.droidflyer.presentation.core.ScreenChanger
import com.unhappychoice.droidflyer.presentation.presenter.ChartPresenter
import com.unhappychoice.droidflyer.presentation.presenter.SettingsPresenter
import com.unhappychoice.droidflyer.presentation.screen.ChartScreen
import com.unhappychoice.droidflyer.presentation.screen.SettingsScreen
import com.unhappychoice.droidflyer.presentation.view.core.HasMenu
import flow.Flow
import flow.KeyDispatcher
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.activity_main.*
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner

class MainActivity : AppCompatActivity() {
    val module = Kodein {
        bind<SettingsPresenter>() with provider { SettingsPresenter(this@MainActivity) }
        bind<ChartPresenter>() with provider { ChartPresenter(this@MainActivity) }
    }

    private val bag = CompositeDisposable()

    private val scope: MortarScope by lazy {
        MortarScope.buildChild(applicationContext)
            .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
            .build("activity_scope")
    }

    override fun getSystemService(name: String?): Any? {
        return when (scope.hasService(name)) {
            true -> scope.getService(name)
            false -> super.getSystemService(name)
        }
    }

    override fun attachBaseContext(baseContext: Context) {
        super.attachBaseContext(getFlowContext(baseContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BundleServiceRunner.getBundleServiceRunner(this).onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bottom_navigation.setOnNavigationItemSelectedListener {
            when(it.itemId) {
                R.id.chartIcon -> Flow.get(this).set(ChartScreen())
                R.id.orderIcon -> {}
                R.id.settingsIcon -> Flow.get(this).set(SettingsScreen())
            }
            true
        }
    }

    override fun onDestroy() {
        scope.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        BundleServiceRunner.getBundleServiceRunner(this).onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (!Flow.get(this).goBack()) {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        (getCurrentView() as? HasMenu)?.onCreateOptionsMenu(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.home -> Flow.get(this).goBack()
        }
        (getCurrentView() as? HasMenu)?.onOptionsItemSelected(item)
        return true
    }

    private fun getFlowContext(baseContext: Context): Context =
        Flow.configure(baseContext, this)
            .dispatcher(KeyDispatcher.configure(this, ScreenChanger(this)).build())
            .defaultKey(ChartScreen())
            .keyParceler(GsonParceler())
            .install()

    private fun getCurrentView(): View? = containerView.getChildAt(0)
}
