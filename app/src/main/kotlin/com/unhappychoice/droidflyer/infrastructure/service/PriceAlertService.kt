package com.unhappychoice.droidflyer.infrastructure.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.domain.service.PriceAlert
import com.unhappychoice.droidflyer.domain.service.latestMarketPrice
import com.unhappychoice.droidflyer.extension.filterNotNull
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.notification.PRICE_ALERT_SERVICE_NOTIFICATION_ID
import com.unhappychoice.droidflyer.infrastructure.notification.priceAlertServiceNotification
import com.unhappychoice.droidflyer.infrastructure.notification.priceAlertTriggeredNotification
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.PriceAlertPreference
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class PriceAlertService : Service() {
    private val preference by lazy { PriceAlertPreference(applicationContext) }
    private val client by lazy {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        RealtimeClient(gson, APITokenPreference(applicationContext))
    }
    private val bag = CompositeDisposable()
    private var monitor: PriceAlertMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(PRICE_ALERT_SERVICE_NOTIFICATION_ID, priceAlertServiceNotification())
        monitor = PriceAlertMonitor(
            preference,
            client.executions.map(::latestMarketPrice).map { it.yen }.filterNotNull(),
            ::notifyTriggered
        )
        preference.alertsChanged.startWith(preference.read())
            .subscribeNext { if (it.isEmpty()) stopSelf() }
            .addTo(bag)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        bag.dispose()
        monitor?.dispose()
        client.disconnect()
        super.onDestroy()
    }

    private fun notifyTriggered(alert: PriceAlert) {
        // Offset from the foreground service notification's fixed id so a triggered alert never replaces it.
        val notificationId = PRICE_ALERT_SERVICE_NOTIFICATION_ID + alert.id.toInt()
        NotificationManagerCompat.from(this).notify(notificationId, priceAlertTriggeredNotification(alert))
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PriceAlertService::class.java))
        }
    }
}
