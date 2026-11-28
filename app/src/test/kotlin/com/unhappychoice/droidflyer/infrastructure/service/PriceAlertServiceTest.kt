package com.unhappychoice.droidflyer.infrastructure.service

import android.app.Application
import android.content.pm.ServiceInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PriceAlertServiceTest {
    @Test
    fun stopsItselfWhenDataSyncTimesOut() {
        val service = Robolectric.buildService(PriceAlertService::class.java).get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
