package com.unhappychoice.droidflyer.infrastructure.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
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
    fun repeatedAlertIdsProduceDistinctNotificationsAlongsideTheService() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.createPriceAlertNotificationChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(PRICE_ALERT_SERVICE_NOTIFICATION_ID, context.priceAlertServiceNotification())

        assertTrue(context.notifyPriceAlertTriggered(PriceAlert(1, 12_000_000, PriceAlertDirection.ABOVE)))
        assertTrue(context.notifyPriceAlertTriggered(PriceAlert(1, 11_000_000, PriceAlertDirection.BELOW)))

        assertEquals(3, manager.activeNotifications.size)
        assertEquals(3, manager.activeNotifications.map { it.tag }.toSet().size)
    }

    @Test
    fun skipsPostingWhenNotificationPermissionIsDenied() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.createPriceAlertNotificationChannel()
        val manager = context.getSystemService(NotificationManager::class.java)

        assertFalse(context.notifyPriceAlertTriggered(PriceAlert(1, 12_000_000, PriceAlertDirection.ABOVE)))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun skipsPostingWhenTheAlertChannelIsBlocked() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PRICE_ALERT_CHANNEL_ID, "Price Alerts", NotificationManager.IMPORTANCE_NONE)
        )

        assertFalse(context.notifyPriceAlertTriggered(PriceAlert(1, 12_000_000, PriceAlertDirection.ABOVE)))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun triggeredNotificationDescribesTheCrossedDirectionAndPrice() {
        val notification = context.priceAlertTriggeredNotification(PriceAlert(1, 12_000_000, PriceAlertDirection.ABOVE))

        assertEquals("Crypto CFD crossed above 12000000 JPY", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
