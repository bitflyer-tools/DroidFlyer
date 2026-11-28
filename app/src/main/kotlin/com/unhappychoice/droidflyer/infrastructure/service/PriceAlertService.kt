package com.unhappychoice.droidflyer.infrastructure.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.unhappychoice.droidflyer.DroidFlyerApplication
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.extension.subscribeNext
import com.unhappychoice.droidflyer.infrastructure.bitflyer.CryptoCfdExecutionFeed
import com.unhappychoice.droidflyer.infrastructure.notification.PRICE_ALERT_SERVICE_NOTIFICATION_ID
import com.unhappychoice.droidflyer.infrastructure.notification.notifyPriceAlertTriggered
import com.unhappychoice.droidflyer.infrastructure.notification.priceAlertServiceNotification
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo

internal class PriceAlertService : Service() {
    private val preference by lazy { (application as DroidFlyerApplication).priceAlertPreference }
    private val executionFeed by lazy {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        CryptoCfdExecutionFeed(gson)
    }
    private val bag = CompositeDisposable()
    private var monitor: PriceAlertMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(PRICE_ALERT_SERVICE_NOTIFICATION_ID, priceAlertServiceNotification())
        monitor = PriceAlertMonitor(
            preference,
            executionFeed.events.observeOn(androidMainThreadScheduler())
        ) { alert -> notifyPriceAlertTriggered(alert) }
        executionFeed.connect()
        preference.alertsChanged.startWith(preference.read())
            .subscribeNext { if (it.isEmpty()) stopSelf() }
            .addTo(bag)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        bag.dispose()
        monitor?.dispose()
        executionFeed.disconnect()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PriceAlertService::class.java))
            } catch (exception: IllegalStateException) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    exception !is ForegroundServiceStartNotAllowedException
                ) throw exception
            }
        }
    }
}
