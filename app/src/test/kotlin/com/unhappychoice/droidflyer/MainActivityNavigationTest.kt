package com.unhappychoice.droidflyer

import android.content.Context
import android.view.View
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.unhappychoice.droidflyer.databinding.OrderViewBinding
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import com.unhappychoice.droidflyer.presentation.view.ChartView
import com.unhappychoice.droidflyer.presentation.view.OrderView
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import flow.Flow
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.socket.client.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.direct
import org.kodein.di.instance
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [MainActivityNavigationTest.DisconnectedSocket::class],
    instrumentedPackages = ["io.socket.client"]
)
class MainActivityNavigationTest {
    @Before
    fun pauseBackgroundRequests() {
        DisconnectedSocket.connections.clear()
        val scheduler = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
    }

    @Test
    fun startupContinuesWhenLegacyCredentialsCannotBeEncrypted() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("api_token", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("key", "legacy-key").putString("secret", "legacy-secret").commit())

        // Robolectric does not provide the AndroidKeyStore provider used by the activity.
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertDestination(controller.get(), R.id.chartIcon, ChartView::class.java, 1)
            assertEquals("legacy-key", preferences.getString("key", null))
            assertEquals("legacy-secret", preferences.getString("secret", null))
        }

        val credentials = testAPITokenPreference(application).read()
        assertEquals("legacy-key", credentials.key)
        assertEquals("legacy-secret", credentials.secret)
        assertFalse(preferences.contains("key"))
        assertFalse(preferences.contains("secret"))
    }

    @Test
    fun backChecksTheDestinationAndDoesNotAddHistory() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertDestination(activity, R.id.chartIcon, ChartView::class.java, 1)
            activity.tapTab(R.id.orderIcon)
            assertDestination(activity, R.id.orderIcon, OrderView::class.java, 2)
            activity.tapTab(R.id.settingsIcon)
            assertDestination(activity, R.id.settingsIcon, SettingsView::class.java, 3)

            activity.onBackPressed()
            assertDestination(activity, R.id.orderIcon, OrderView::class.java, 2)
            activity.onBackPressed()
            assertDestination(activity, R.id.chartIcon, ChartView::class.java, 1)
        }
    }

    @Test
    fun reattachingOrderKeepsOnePagerAndOneListenerInEachDirection() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            val parent = activity.binding.containerView
            val order = OrderViewBinding.bind(parent.getChildAt(0))
            val adapter = order.viewPager.adapter

            repeat(3) {
                parent.removeView(order.root)
                parent.addView(order.root)

                assertEquals(adapter, order.viewPager.adapter)
                assertEquals(4, order.tabLayout.tabCount)
                assertEquals(4, order.viewPager.adapter?.count)
                assertEquals(1, ReflectionHelpers.getField<List<TabLayout.OnTabSelectedListener>>(
                    order.tabLayout, "selectedListeners"
                ).size)
                assertEquals(1, ReflectionHelpers.getField<List<ViewPager.OnPageChangeListener>>(
                    order.viewPager, "mOnPageChangeListeners"
                ).size)
                order.tabLayout.getTabAt(1)?.select()
                assertEquals(1, order.viewPager.currentItem)
                order.viewPager.setCurrentItem(0, false)
                assertEquals(0, order.tabLayout.selectedTabPosition)
            }
        }
    }

    @Test
    fun recreationChecksTheRestoredDestinationWithStaleWidgetState() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.tapTab(R.id.orderIcon)
            activity.binding.bottomNavigation.apply {
                isSaveFromParentEnabled = true
                menu.findItem(R.id.settingsIcon).isChecked = true
            }

            controller.recreate()

            assertDestination(controller.get(), R.id.orderIcon, OrderView::class.java, 2)
        }
    }

    @Test
    fun recreationReplacesTheRealtimeClientAndDetachesItsSocketListeners() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val oldClient = controller.get().module.direct.instance<RealtimeClient>()
            val oldSocket = DisconnectedSocket.connections.single()
            assertTrue(oldSocket.hasListeners("child_order_events"))

            controller.recreate()

            val newClient = controller.get().module.direct.instance<RealtimeClient>()
            assertNotSame(oldClient, newClient)
            assertFalse(oldSocket.hasListeners("child_order_events"))
            assertTrue(DisconnectedSocket.connections.last().hasListeners("child_order_events"))
        }
    }

    private fun MainActivity.tapTab(itemId: Int) {
        binding.bottomNavigation.findViewById<View>(itemId).performClick()
    }

    private fun assertDestination(activity: MainActivity, itemId: Int, viewClass: Class<*>, historySize: Int) {
        assertEquals(viewClass, activity.binding.containerView.getChildAt(0).javaClass)
        val navigation = activity.binding.bottomNavigation
        assertEquals(itemId, navigation.selectedItemId)
        assertTrue(navigation.menu.findItem(itemId).isChecked)
        assertEquals(historySize, Flow.get(activity).history.size())
    }

    @Implements(value = Socket::class, isInAndroidSdk = false)
    class DisconnectedSocket {
        @RealObject
        private lateinit var socket: Socket

        @Implementation
        fun connect(): Socket = socket.also { connections.add(it) }

        companion object {
            val connections = mutableListOf<Socket>()
        }
    }
}
