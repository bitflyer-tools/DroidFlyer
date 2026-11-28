package com.unhappychoice.droidflyer.infrastructure.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unhappychoice.droidflyer.R
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.PriceAlertDirection
import java.util.UUID

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

internal fun Context.notifyPriceAlertTriggered(alert: PriceAlert): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return false

    val manager = NotificationManagerCompat.from(this)
    if (!manager.canPostPriceAlert()) return false

    return try {
        val tag = "price-alert-${UUID.randomUUID()}"
        manager.notify(tag, PRICE_ALERT_SERVICE_NOTIFICATION_ID, priceAlertTriggeredNotification(alert))
        true
    } catch (_: SecurityException) {
        false
    }
}

private fun NotificationManagerCompat.canPostPriceAlert(): Boolean {
    if (!areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

    val importance = getNotificationChannel(PRICE_ALERT_CHANNEL_ID)?.importance
    return importance != null && importance != NotificationManager.IMPORTANCE_NONE
}

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
