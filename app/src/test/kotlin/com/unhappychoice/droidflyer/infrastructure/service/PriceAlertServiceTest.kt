package com.unhappychoice.droidflyer.infrastructure.service

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
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
}
