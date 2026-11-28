package com.unhappychoice.droidflyer

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Looper
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OrderFillNotificationControllerTest {
    private val preference = testAPITokenPreference(RuntimeEnvironment.getApplication())
    private val states = mutableListOf<Boolean>()
    private var allowed = false
    private val controller = OrderFillNotificationController(preference, { allowed }) { states.add(it) }

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun linkingDoesNotStartMonitoringAndUnlinkStopsAnExplicitSession() {
        val subscription = controller.observeUnlink()
        try {
            allowed = true
            preference.save(APICredentials("key", "secret"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<Boolean>(), states)

            controller.startMonitoring()
            controller.startMonitoring()
            assertEquals(listOf(true), states)

            preference.saveIfChanged(APICredentials("new-key", "new-secret"), preference.credentialsGeneration)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), states)

            preference.clear()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), states)
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun permissionAndExplicitStopControlMonitoring() {
        controller.startMonitoring()
        assertEquals(emptyList<Boolean>(), states)

        allowed = true
        controller.startMonitoring()
        controller.stopMonitoring()
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun disablingNotificationsStopsMonitoring() {
        allowed = true
        controller.startMonitoring()
        allowed = false
        controller.stopIfNotificationsDisabled()

        assertEquals(listOf(true, false), states)
    }

    @Test
    @Config(sdk = [35])
    fun deniedServiceStartLeavesMonitoringOff() {
        val controller = OrderFillNotificationController(preference, { true }) {
            throw ForegroundServiceStartNotAllowedException("App is in the background")
        }

        controller.startMonitoring()

        assertEquals(false, controller.running.value)
    }
}
