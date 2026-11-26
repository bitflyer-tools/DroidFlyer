package com.unhappychoice.droidflyer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unhappychoice.droidflyer.domain.service.CurrentStatusService
import com.unhappychoice.droidflyer.infrastructure.bitflyer.RealtimeClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClient
import com.unhappychoice.droidflyer.infrastructure.bitflyer.http.APIClientV1
import com.unhappychoice.droidflyer.infrastructure.bitflyer.model.ChildOrderEvent
import com.unhappychoice.droidflyer.infrastructure.preference.APITokenPreference
import com.unhappychoice.droidflyer.infrastructure.preference.StopSubmissionPreference
import com.unhappychoice.droidflyer.presentation.executionMessage
import org.kodein.di.direct
import org.kodein.di.instance
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps a [RealtimeSession] alive independent of [MainActivity] so a system notification is
 * posted for order fills while the app is backgrounded. Started and stopped by
 * [OrderFillNotificationController] as the linked account changes.
 */
class OrderFillNotificationService : Service() {
    private var session: RealtimeSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, watchingNotification())
        session = sessionFactory(application as DroidFlyerApplication).apply {
            notifyExecutions(::notifyFill)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun notifyFill(event: ChildOrderEvent) {
        val notification = NotificationCompat.Builder(this, FILL_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(executionMessage(event))
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(nextFillNotificationId.incrementAndGet(), notification)
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
        private const val FILL_CHANNEL_ID = "order_fills"
        private const val SERVICE_CHANNEL_ID = "order_fill_service"
        private const val SERVICE_NOTIFICATION_ID = 1
        private val nextFillNotificationId = AtomicInteger(SERVICE_NOTIFICATION_ID)

        /** Overridable by tests so they can supply a [RealtimeSession] backed by a fake socket. */
        internal var sessionFactory: (DroidFlyerApplication) -> RealtimeSession = ::defaultSession

        private fun defaultSession(application: DroidFlyerApplication): RealtimeSession {
            val module = application.module
            val preference = module.direct.instance<APITokenPreference>()
            val api = module.direct.instance<APIClient>()
            val stopSubmissions = StopSubmissionPreference(application)
            val client = RealtimeClient(module.direct.instance(), preference)
            val status = CurrentStatusService(module.direct.instance<APIClientV1>(), client, null).apply {
                initializeAccount {
                    val credentials = preference.read()
                    api.privateStatusClient(credentials)?.let { client ->
                        client to stopSubmissions.forApiKey(credentials.key)
                    }
                }
            }
            return RealtimeSession(client, status)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OrderFillNotificationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderFillNotificationService::class.java))
        }
    }
}
