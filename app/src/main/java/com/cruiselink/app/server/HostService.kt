package com.cruiselink.app.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cruiselink.app.MainActivity

class HostService : Service() {
    companion object {
        private const val CHANNEL_ID = "cruiselink_host_channel"
        private const val NOTIF_ID = 2001
        const val ACTION_START = "com.cruiselink.app.START_HOST"
        const val ACTION_STOP = "com.cruiselink.app.STOP_HOST"

        @Volatile
        var runningUrl: String? = null
            private set

        fun startHost(context: Context) {
            val intent = Intent(context, HostService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopHost(context: Context) {
            val intent = Intent(context, HostService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: LanChatServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val ip = LanChatServer.getLocalLanIp()
        val url = "http://$ip:8787"
        startForeground(NOTIF_ID, buildNotification(url))

        if (server == null || server?.isRunning != true) {
            val s = LanChatServer(applicationContext, 8787)
            s.start()
            server = s
            runningUrl = url
        }
        return START_STICKY
    }

    private fun stopServer() {
        server?.stop()
        server = null
        runningUrl = null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "CruiseLink 內網主機服務",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(url: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CruiseLink 內網主機運行中")
            .setContentText("iOS / 瀏覽器連線網址：$url")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }
}
