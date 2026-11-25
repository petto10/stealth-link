package dev.stealthcompanion.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.stealthcompanion.R

class SocialConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        BleRuntime.init(application)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(SETTINGS_ADDRESS)
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BleRuntime.hasConnectPermission()) {
            BleRuntime.foregroundFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = sessionNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            BleRuntime.foregroundFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        BleRuntime.serviceStarted(address, intent.getStringExtra(SOCIAL_ADDRESS))
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        BleRuntime.serviceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun sessionNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.ble_service_channel), NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val stopIntent = Intent(this, SocialConnectionService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.companion_name))
            .setContentText(getString(R.string.ble_service_description))
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ble_service_stop), stop).build())
            .build()
    }

    internal companion object {
        const val SETTINGS_ADDRESS = "settings_address"
        const val SOCIAL_ADDRESS = "social_address"
        private const val ACTION_STOP = "dev.stealthcompanion.ble.STOP"
        private const val NOTIFICATION_ID = 410
        private const val CHANNEL_ID = "controller_connection"
    }
}
