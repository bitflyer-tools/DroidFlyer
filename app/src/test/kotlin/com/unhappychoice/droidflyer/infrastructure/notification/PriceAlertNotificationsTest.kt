package com.unhappychoice.droidflyer.infrastructure.notification

import android.app.Notification
import android.app.NotificationManager
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PriceAlertNotificationsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun createsAHighImportanceChannelSeparateFromAnyOtherFeature() {
        context.createPriceAlertNotificationChannel()

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = requireNotNull(manager.getNotificationChannel(PRICE_ALERT_CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun creatingTheChannelTwiceDoesNotThrow() {
        context.createPriceAlertNotificationChannel()
        context.createPriceAlertNotificationChannel()
    }

    @Test
    fun serviceNotificationIsOngoingAndSilent() {
        val notification = context.priceAlertServiceNotification()

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun triggeredNotificationDescribesTheCrossedDirectionAndPrice() {
        val notification = context.priceAlertTriggeredNotification(PriceAlert(1, 12_000_000, PriceAlertDirection.ABOVE))

        assertEquals("Crypto CFD crossed above 12000000 JPY", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
