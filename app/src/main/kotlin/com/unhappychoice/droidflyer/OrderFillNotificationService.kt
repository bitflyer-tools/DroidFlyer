package com.unhappychoice.droidflyer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unhappychoice.droidflyer.extension.androidMainThreadScheduler
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.presentation.executionMessage
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Watches for order outcomes while the account has open Crypto CFD orders. */
class OrderFillNotificationService : Service() {
    private var session: RealtimeSession? = null
    private var orderChecks: Disposable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as DroidFlyerApplication
        if (intent?.action != ACTION_START || !notificationsAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (orderChecks == null) {
            createNotificationChannels()
            if (!notificationsAllowed(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            startForeground(SERVICE_NOTIFICATION_ID, watchingNotification())
            app.orderFillNotifications.running.value = true
            watchOpenOrders(app)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        orderChecks?.dispose()
        session?.close()
        session = null
        (application as DroidFlyerApplication).orderFillNotifications.running.value = false
        super.onDestroy()
    }

    private fun watchOpenOrders(app: DroidFlyerApplication) {
        orderChecks = Observable.defer { openOrdersCheck(app) }
            .subscribeOn(Schedulers.io())
            .repeatWhen { completed -> completed.delay(ORDER_CHECK_SECONDS, TimeUnit.SECONDS) }
            .observeOn(androidMainThreadScheduler())
            .takeWhile { it && notificationsAllowed(this) }
            .subscribe(
                {
                    if (session == null) {
                        session = sessionFactory(app).apply {
                            notifyOrderOutcomes { notifyOutcome(outcomeMessage(it)) }
                            notifyParentOrderCancellations {
                                notifyOutcome(getString(R.string.order_notification_canceled, it))
                            }
                        }
                    }
                },
                { stopSelf() },
                { stopSelf() }
            )
    }

    @SuppressLint("MissingPermission")
    private fun notifyOutcome(message: String) {
        if (!notificationsAllowed(this)) {
            stopSelf()
            return
        }
        val notification = NotificationCompat.Builder(this, FILL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(nextFillNotificationId.incrementAndGet(), notification)
    }

    private fun outcomeMessage(event: ChildOrderEvent): String = when (event.eventType) {
        "ORDER_FAILED" -> getString(R.string.order_notification_rejected, event.productCode)
        "CANCEL" -> getString(R.string.order_notification_canceled, event.productCode)
        else -> executionMessage(event)
    }

    private fun watchingNotification() =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.order_fill_service_notification_title))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FILL_CHANNEL_ID, getString(R.string.order_fill_notification_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.order_fill_notification_channel_description)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL_ID, getString(R.string.order_fill_service_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        internal const val ACTION_START = "com.unhappychoice.droidflyer.action.START_ORDER_MONITORING"
        private const val ORDER_CHECK_SECONDS = 15L
        private const val FILL_CHANNEL_ID = "order_fills"
        private const val SERVICE_CHANNEL_ID = "order_fill_service"
        private const val SERVICE_NOTIFICATION_ID = 1
        private val nextFillNotificationId = AtomicInteger(SERVICE_NOTIFICATION_ID)

        /** Overridable by tests so they can supply a [RealtimeSession] backed by a fake socket. */
        internal var sessionFactory: (DroidFlyerApplication) -> RealtimeSession = ::defaultSession

        internal var openOrdersCheck: (DroidFlyerApplication) -> Observable<Boolean> = ::hasOpenOrders

        private fun defaultSession(application: DroidFlyerApplication): RealtimeSession {
            val client = RealtimeClient(application.module.direct.instance(), application.tokenPreference, publicFeed = false)
            return RealtimeSession(client).apply { watchSavedAccount() }
        }

        private fun hasOpenOrders(app: DroidFlyerApplication): Observable<Boolean> {
            val credentials = app.tokenPreference.read()
            val api = app.module.direct.instance<APIClient>().privateStatusClient(credentials)
                ?: return Observable.just(false)
            return api.getParentOrders(orderState = "ACTIVE", count = 1)
                .flatMap { parents ->
                    if (parents.isNotEmpty()) Observable.just(true)
                    else api.getChildOrders("FX_BTC_JPY", count = 1).map { it.isNotEmpty() }
                }
                .timeout(ORDER_CHECK_SECONDS, TimeUnit.SECONDS)
        }

        internal fun notificationsAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            val manager = context.getSystemService(NotificationManager::class.java)
            return listOf(FILL_CHANNEL_ID, SERVICE_CHANNEL_ID).none {
                manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, OrderFillNotificationService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderFillNotificationService::class.java))
        }
    }
}
