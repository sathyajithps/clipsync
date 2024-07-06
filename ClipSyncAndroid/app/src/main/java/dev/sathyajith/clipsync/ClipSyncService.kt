package dev.sathyajith.clipsync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService

class ClipSyncService : Service() {
    var multicastLink: MulticastLink? = null
    private var mIsServiceStarted = false
    private val binder = LocalBinder()


    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ServiceActions.START.name -> startService()
                ServiceActions.STOP.name -> stopService()
                else -> {}
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent =
            Intent(applicationContext, ClipSyncService::class.java).also {
                it.setPackage(packageName)
            }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager =
            applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
    }

    override fun onCreate() {
        super.onCreate()

        val notification = createNotification()
        startForeground(1, notification)
    }

    private fun startService() {
        if (mIsServiceStarted) return

        val wifiManager = applicationContext.getSystemService<WifiManager>()!!
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>()!!

        multicastLink?.dispose()
        multicastLink = MulticastLink(ipType = IpType.IPV4, wifiManager, clipboardManager)

        mIsServiceStarted = true

        setServiceState(this, ServiceState.STARTED)
    }

    private fun stopService() {
        try {
            multicastLink?.dispose()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } catch (e: Exception) {
            Log.e(CST, "Error while stopping service: ${e.stackTrace}")
        }

        mIsServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "CLIP_SYNC_ID"

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            notificationChannelId,
            "Clip Sync Channel",
            NotificationManager.IMPORTANCE_HIGH
        ).let {
            it.description = "Channel for clip sync service"
            it.enableLights(true)
            it.lightColor = Color.RED
            it.enableVibration(true)
            it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            it
        }
        notificationManager.createNotificationChannel(channel)

        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let {
                it.action = COPY_FROM_CB
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }

        val builder: Notification.Builder = Notification.Builder(
            this,
            notificationChannelId
        )

        return builder
            .setContentTitle("Clip Sync")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Ticker text")
            .build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ClipSyncService = this@ClipSyncService
    }

}