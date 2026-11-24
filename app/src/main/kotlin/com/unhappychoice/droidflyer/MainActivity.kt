package com.unhappychoice.droidflyer

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnAttach
import com.google.android.material.snackbar.Snackbar
import com.unhappychoice.droidflyer.databinding.ActivityMainBinding
import com.unhappychoice.droidflyer.di.activityModule
import com.unhappychoice.droidflyer.di.applicationModule
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.presentation.executionMessage
import com.unhappychoice.droidflyer.presentation.core.GsonParceler
import com.unhappychoice.droidflyer.presentation.core.ScreenChanger
import com.unhappychoice.droidflyer.presentation.screen.ChartScreen
import com.unhappychoice.droidflyer.presentation.screen.OrderScreen
import com.unhappychoice.droidflyer.presentation.screen.SettingsScreen
import com.unhappychoice.droidflyer.presentation.screen.core.Screen
import com.unhappychoice.droidflyer.presentation.style.DefaultStyle
import com.unhappychoice.droidflyer.presentation.view.core.HasMenu
import com.unhappychoice.droidflyer.presentation.view.core.protectTradingTouches
import flow.Flow
import flow.KeyDispatcher
import mortar.MortarScope
import mortar.bundler.BundleServiceRunner
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

class MainActivity : AppCompatActivity() {
    val module by lazy {
        DI {
            import(applicationModule(application as DroidFlyerApplication))
            import(activityModule(this@MainActivity))
        }
    }

    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val realtimeSession = lazy {
        val api = module.direct.instance<APIClient>()
        val preference = module.direct.instance<APITokenPreference>()
        RealtimeSession(module.direct.instance(), module.direct.instance()) { api.privateStatusClient(preference.read()) }
    }

    private val chartScreen by lazy { ChartScreen() }
    private val bottomNavigationScreens by lazy {
        mapOf(
            R.id.chartIcon to chartScreen,
            R.id.orderIcon to OrderScreen(),
            R.id.settingsIcon to SettingsScreen()
        )
    }

    private val scope: MortarScope by lazy {
        MortarScope.buildChild(applicationContext)
            .withService(BundleServiceRunner.SERVICE_NAME, BundleServiceRunner())
            .build("activity_scope")
    }

    override fun getSystemService(name: String): Any? {
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
        // Flow restores the destination and its checked item together.
        binding.bottomNavigation.isSaveFromParentEnabled = false
        setContentView(binding.root)
        window.protectTradingTouches()
        setupStyle()
        realtimeSession.value.notifyExecutions(::showExecution)

        binding.bottomNavigation.setOnItemSelectedListener {
            bottomNavigationScreens[it.itemId]?.let { screen -> Flow.get(this).set(screen) }
            // The Flow traversal checks the item when its screen is rendered.
            false
        }
    }

    internal fun updateBottomNavigation(screen: Screen) {
        val itemId = bottomNavigationScreens.entries.firstOrNull { it.value == screen }?.key ?: return
        binding.bottomNavigation.menu.findItem(itemId).isChecked = true
    }

    internal fun showSnackbar(message: String, duration: Int) {
        binding.containerView.doOnAttach { Snackbar.make(it, message, duration).show() }
    }

    override fun onDestroy() {
        if (realtimeSession.isInitialized()) realtimeSession.value.close()
        scope.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> Flow.get(this).goBack()
        }
        (getCurrentView() as? HasMenu)?.onOptionsItemSelected(item)
        return true
    }

    private fun getFlowContext(baseContext: Context): Context =
        Flow.configure(baseContext, this)
            .dispatcher(KeyDispatcher.configure(this, ScreenChanger(this)).build())
            .defaultKey(chartScreen)
            .keyParceler(GsonParceler())
            .install()

    private fun getCurrentView(): View? = binding.containerView.getChildAt(0)

    private fun setupStyle() {
        supportActionBar?.elevation = 0f
        supportActionBar?.setBackgroundDrawable(ColorDrawable(DefaultStyle.darkerPrimaryColor))
        binding.bottomNavigation.setBackgroundColor(DefaultStyle.darkerPrimaryColor)
        binding.containerView.setBackgroundColor(DefaultStyle.primaryColor)
    }

    private fun showExecution(event: ChildOrderEvent) {
        showSnackbar(executionMessage(event), Snackbar.LENGTH_SHORT)
    }
}
