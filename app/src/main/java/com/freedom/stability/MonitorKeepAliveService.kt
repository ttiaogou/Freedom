package com.freedom.stability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.freedom.MainActivity
import com.freedom.R
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay

/**
 * User-visible foreground companion for an active Freedom monitoring session.
 * AccessibilityService remains the actual event-listening component.
 */
class MonitorKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MonitorStore.setRunning(this, false)
            MonitorStore.addLog(this, "已通过运行通知停止监听")
            MonitorOverlay.hide()
            sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!MonitorStore.isRunning(this) || !MonitorStore.isKeepAliveEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            START_STICKY
        } catch (error: Exception) {
            MonitorStore.addLog(
                this,
                "前台保活服务启动失败：${error.javaClass.simpleName}"
            )
            sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(packageName))
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, MonitorKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_freedom)
            .setContentTitle("Freedom 正在运行")
            .setContentText("无障碍实时监控正在保持运行")
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_freedom,
                    "停止",
                    stopIntent
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Freedom 运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Freedom 正在执行的实时监控任务"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "freedom_monitor_status"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_STOP = "com.freedom.action.STOP_KEEP_ALIVE"

        /** Start only from a user-visible action or after the accessibility overlay is restored. */
        fun start(context: Context): Boolean {
            if (!MonitorStore.isRunning(context) || !MonitorStore.isKeepAliveEnabled(context)) {
                return false
            }
            return try {
                val intent = Intent(context, MonitorKeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (error: Exception) {
                MonitorStore.addLog(
                    context,
                    "无法启动前台保活服务：${error.javaClass.simpleName}"
                )
                false
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MonitorKeepAliveService::class.java))
            }
        }

        const val BOOT_CHANNEL_ID = CHANNEL_ID
    }
}
