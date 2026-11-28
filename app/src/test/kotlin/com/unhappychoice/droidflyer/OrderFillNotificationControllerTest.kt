package com.unhappychoice.droidflyer

import android.os.Looper
import com.unhappychoice.droidflyer.infrastructure.preference.APICredentials
import com.unhappychoice.droidflyer.infrastructure.preference.testAPITokenPreference
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
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
    private val controller = OrderFillNotificationController(preference) { states.add(it) }

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun startsOnLinkWhileResumedAndStopsOnUnlinkInBackground() {
        val subscription = controller.start()
        try {
            controller.onAppResumed()
            assertEquals(emptyList<Boolean>(), states)

            preference.save(APICredentials("key", "secret"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), states)

            preference.save(APICredentials("key", "secret"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), states)

            controller.onAppPaused()
            assertEquals(listOf(true), states)
            preference.clear()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false), states)
        } finally {
            subscription.dispose()
        }
    }

    @Test
    fun startsSavedAccountOnlyWhenResumedAndRestartsOnReturn() {
        val subscription = controller.start()
        try {
            preference.save(APICredentials("key", "secret"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(emptyList<Boolean>(), states)

            controller.onAppResumed()
            controller.onAppPaused()
            assertEquals(listOf(true), states)

            controller.onAppResumed()
            assertEquals(listOf(true, true), states)
        } finally {
            subscription.dispose()
        }
    }
}
