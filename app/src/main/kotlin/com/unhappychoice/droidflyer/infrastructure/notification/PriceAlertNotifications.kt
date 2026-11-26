package com.unhappychoice.droidflyer.infrastructure.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection

internal const val PRICE_ALERT_CHANNEL_ID = "price_alerts"
internal const val PRICE_ALERT_SERVICE_NOTIFICATION_ID = 1

internal fun Context.createPriceAlertNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
        PRICE_ALERT_CHANNEL_ID,
        getString(R.string.price_alert_channel_name),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = getString(R.string.price_alert_channel_description)
    }
    manager.createNotificationChannel(channel)
}

internal fun Context.priceAlertServiceNotification(): Notification =
    NotificationCompat.Builder(this, PRICE_ALERT_CHANNEL_ID)
        .setContentTitle(getString(R.string.price_alert_service_title))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

internal fun Context.priceAlertTriggeredNotification(alert: PriceAlert): Notification {
    val direction = getString(
        when (alert.direction) {
            PriceAlertDirection.ABOVE -> R.string.alert_direction_above
            PriceAlertDirection.BELOW -> R.string.alert_direction_below
        }
    )
    return NotificationCompat.Builder(this, PRICE_ALERT_CHANNEL_ID)
        .setContentTitle(getString(R.string.price_alert_triggered_title))
        .setContentText(getString(R.string.price_alert_triggered_body, direction.lowercase(), alert.targetPrice))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
}
