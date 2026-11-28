package com.unhappychoice.droidflyer.infrastructure.service

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PriceAlertServiceTest {
    @Test
    fun declaresSpecialUseForContinuousMonitoring() {
        val application = RuntimeEnvironment.getApplication()
        val service = application.packageManager.getServiceInfo(
            ComponentName(application, PriceAlertService::class.java),
            PackageManager.ComponentInfoFlags.of(0)
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
    }

    @Test
    fun toleratesAnAndroidBackgroundStartRejection() {
        PriceAlertService.start(rejectingContext(ForegroundServiceStartNotAllowedException("Background start denied")))
    }

    @Test
    fun propagatesUnexpectedStartFailures() {
        val failure = IllegalStateException("Unexpected start failure")

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            PriceAlertService.start(rejectingContext(failure))
        })
    }

    private fun rejectingContext(failure: IllegalStateException) =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startForegroundService(service: Intent): ComponentName = throw failure
        }
}
