package com.unhappychoice.droidflyer

import android.view.View
import com.unhappychoice.droidflyer.presentation.view.ChartView
import com.unhappychoice.droidflyer.presentation.view.OrderView
import com.unhappychoice.droidflyer.presentation.view.SettingsView
import flow.Flow
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.socket.client.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [MainActivityNavigationTest.DisconnectedSocket::class],
    instrumentedPackages = ["io.socket.client"]
)
class MainActivityNavigationTest {
    @Before
    fun pauseBackgroundRequests() {
        val scheduler = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { scheduler }
    }

    @After
    fun resetSchedulers() {
        RxJavaPlugins.reset()
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
        fun connect(): Socket = socket
    }
}
