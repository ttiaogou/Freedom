package com.freedom.stability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.freedom.MainActivity
import com.freedom.R
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.data.MonitorStore

/** Checks the persisted monitoring request after reboot and notifies the user. */
class BootRecoveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!MonitorStore.isRunning(context) || !MonitorStore.isBootCheckEnabled(context)) return

        val accessibilityEnabled = isAccessibilityEnabled(context)
        val overlayAllowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

        val message = when {
            !accessibilityEnabled -> "设备已重启，但 Freedom 无障碍授权未开启。请打开应用检查授权。"
            !overlayAllowed -> "设备已重启，但 Freedom 悬浮窗授权不可用。请打开应用检查授权。"
            else -> "设备已重启。Freedom 正在等待系统恢复无障碍服务；如悬浮窗未出现，请打开应用检查。"
        }

        postBootCheckNotification(context, message)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, FreedomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component != null &&
                component.packageName == expected.packageName &&
                component.className == expected.className
        }
    }

    private fun postBootCheckNotification(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Freedom 启动检查",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "设备重启后检查 Freedom 的运行授权"
                }
            )
        }

        val openIntent = PendingIntent.getActivity(
            context,
            102,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(R.drawable.ic_freedom)
            .setContentTitle("Freedom 启动检查")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(BOOT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "freedom_boot_check"
        private const val BOOT_NOTIFICATION_ID = 7102
    }
}
