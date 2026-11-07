package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.freedom.accessibility.gesture.GestureTuning
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService

/** Builds the existing settings drawer; all privileged actions are delegated to Activity callbacks. */
internal class ConfigPanel(
    private val activity: Activity,
    private val callbacks: Callbacks
) {
    data class Callbacks(
        val isAccessibilityEnabled: () -> Boolean,
        val dp: (Int) -> Int,
        val roundedBackground: (Int, Float) -> GradientDrawable,
        val timestamp: () -> String,
        val openAppDetailsSettings: () -> Unit,
        val requestOverlayPermission: () -> Unit,
        val notificationPermissionStatus: () -> String,
        val requestNotificationPermission: () -> Unit,
        val showTargetEditor: () -> Unit,
        val requestPhotoPermission: () -> Unit,
        val showPermissionExplanation: () -> Unit,
        val showGestureCalibrationDialog: () -> Unit
    )

    fun show() {
        val dialog = Dialog(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFEFA.toInt())
        }

        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(callbacks.dp(20), callbacks.dp(22), callbacks.dp(14), callbacks.dp(18))
            setBackgroundColor(0xFFFFFEFA.toInt())
        }
        val mark = TextView(activity).apply {
            text = "F"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = callbacks.roundedBackground(0xFF16B889.toInt(), callbacks.dp(12).toFloat())
        }
        top.addView(mark, LinearLayout.LayoutParams(callbacks.dp(48), callbacks.dp(48)))

        val titleBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(callbacks.dp(12), 0, 0, 0)
            addView(TextView(activity).apply {
                text = "Freedom"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF202938.toInt())
            })
            addView(TextView(activity).apply {
                text = "运行与权限配置"
                textSize = 12f
                setTextColor(0xFF778195.toInt())
            })
        }
        top.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(activity).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFF647084.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(callbacks.dp(38), callbacks.dp(44)))
        panel.addView(top)

        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(callbacks.dp(16), callbacks.dp(4), callbacks.dp(16), callbacks.dp(24))
        }
        scroll.addView(rows)

        addConfigSection(rows, "获取权限")
        addConfigActionRow(
            rows, "⌘", "无障碍服务",
            if (callbacks.isAccessibilityEnabled()) "已授权" else "未授权 · 点击前往设置",
            if (callbacks.isAccessibilityEnabled()) "已授权" else "去授权"
        ) {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addConfigActionRow(
            rows, "◉", "悬浮窗权限",
            if (Settings.canDrawOverlays(activity)) "已允许在其他应用上层显示" else "未授权 · 点击前往设置",
            if (Settings.canDrawOverlays(activity)) "已允许" else "去授权"
        ) {
            callbacks.requestOverlayPermission()
        }
        val notificationStatus = callbacks.notificationPermissionStatus()
        addConfigActionRow(
            rows, "♧", "通知权限（可选）",
            notificationStatus,
            if (notificationStatus.startsWith("未授权")) "授权" else "查看"
        ) {
            dialog.dismiss()
            callbacks.requestNotificationPermission()
        }
        addConfigSwitchRow(
            rows, "⛨", "保活服务", "监听运行期间显示持续通知并提高进程优先级",
            MonitorStore.isKeepAliveEnabled(activity)
        ) { enabled ->
            MonitorStore.setKeepAliveEnabled(activity, enabled)
            if (enabled && MonitorStore.isRunning(activity)) {
                if (!MonitorKeepAliveService.start(activity)) {
                    Toast.makeText(activity, "保活服务启动失败，请查看运行记录", Toast.LENGTH_LONG).show()
                }
            } else if (!enabled) {
                MonitorKeepAliveService.stop(activity)
            }
        }
        addConfigSwitchRow(
            rows, "↻", "自启动检测", "设备重启后检查授权状态并提示恢复",
            MonitorStore.isBootCheckEnabled(activity)
        ) { enabled ->
            MonitorStore.setBootCheckEnabled(activity, enabled)
            Toast.makeText(
                activity,
                if (enabled) "已开启开机检查；厂商自启动限制仍需在系统设置中允许"
                else "已关闭开机检查提醒",
                Toast.LENGTH_LONG
            ).show()
        }
        addConfigActionRow(
            rows, "⚙", "后台运行设置", "查看系统电池和应用后台限制设置", "打开"
        ) {
            dialog.dismiss()
            callbacks.openAppDetailsSettings()
        }

        addConfigSection(rows, "脚本配置")
        addConfigSwitchRow(
            rows, "▤", "悬浮日志", "关闭时隐藏整个实时监控悬浮窗；开启时显示悬浮窗及日志",
            MonitorStore.isFloatingLogEnabled(activity)
        ) { enabled ->
            runCatching {
                // Persist the user's choice before changing the WindowManager overlay.
                MonitorStore.setFloatingLogEnabled(activity, enabled)
                // Keep the legacy internal overlay flag synchronized for the accessibility
                // service; the separate "悬浮按钮" setting row has been removed.
                MonitorStore.setOverlayEnabled(activity, enabled)
                if (enabled) {
                    if (MonitorStore.isRunning(activity) && Settings.canDrawOverlays(activity)) {
                        MonitorOverlay.show(activity)
                    }
                } else {
                    // hide() is now synchronous and idempotent on the main thread.
                    MonitorOverlay.hide()
                }
            }.onFailure { error ->
                MonitorStore.addLog(
                    activity,
                    callbacks.timestamp() + "  切换悬浮日志失败：${error.javaClass.simpleName}: " +
                        (error.message ?: "无详细信息")
                )
                Toast.makeText(
                    activity,
                    "切换悬浮日志失败，请查看运行记录",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        addConfigActionRow(
            rows, "◎", "监控目标", MonitorStore.targetPackage(activity), "修改"
        ) {
            dialog.dismiss()
            callbacks.showTargetEditor()
        }

        addConfigSection(rows, "其他权限")
        addConfigActionRow(
            rows, "▧", "照片和视频访问（可选）", "当前监控功能不依赖此权限", "设置"
        ) {
            dialog.dismiss()
            callbacks.requestPhotoPermission()
        }
        addConfigActionRow(
            rows, "ⓘ", "权限用途说明", "查看 Freedom 各项权限的用途", "查看"
        ) {
            callbacks.showPermissionExplanation()
        }

        addConfigSection(rows, "手势参数")
        addConfigActionRow(
            rows,
            "∿",
            "自动采样路径偏移频率",
            GestureTuning.configurationSubtitle(activity),
            if (GestureTuning.isCalibrated(activity)) "查看" else "采样"
        ) {
            dialog.dismiss()
            callbacks.showGestureCalibrationDialog()
        }

        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.gravity = Gravity.START or Gravity.TOP
            params.width = (activity.resources.displayMetrics.widthPixels * 0.82f).toInt()
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.dimAmount = 0.28f
            attributes = params
        }
    }



    private fun addConfigSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(activity).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF315EFB.toInt())
            setPadding(callbacks.dp(4), callbacks.dp(16), callbacks.dp(4), callbacks.dp(8))
        })
    }

    private fun addConfigActionRow(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        actionText: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(callbacks.dp(8), callbacks.dp(9), callbacks.dp(6), callbacks.dp(9))
            background = callbacks.roundedBackground(Color.WHITE, callbacks.dp(10).toFloat())
            setOnClickListener { action() }
        }
        row.addView(configRowIcon(icon), LinearLayout.LayoutParams(callbacks.dp(38), callbacks.dp(38)))
        row.addView(configRowTexts(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(activity).apply {
            text = actionText
            textSize = 11f
            setTextColor(0xFF315EFB.toInt())
            gravity = Gravity.CENTER
            setPadding(callbacks.dp(5), 0, callbacks.dp(3), 0)
        }, LinearLayout.LayoutParams(-2, callbacks.dp(38)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = callbacks.dp(7)
        })
    }

    private fun addConfigSwitchRow(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(callbacks.dp(8), callbacks.dp(7), callbacks.dp(6), callbacks.dp(7))
            background = callbacks.roundedBackground(Color.WHITE, callbacks.dp(10).toFloat())
        }
        row.addView(configRowIcon(icon), LinearLayout.LayoutParams(callbacks.dp(38), callbacks.dp(38)))
        row.addView(configRowTexts(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, callbacks.dp(42)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = callbacks.dp(7)
        })
    }

    private fun configRowIcon(value: String) = TextView(activity).apply {
        text = value
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(0xFF263244.toInt())
    }

    private fun configRowTexts(title: String, subtitle: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(callbacks.dp(4), 0, callbacks.dp(4), 0)
        addView(TextView(activity).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF202938.toInt())
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = 10f
            setTextColor(0xFF7B8494.toInt())
            maxLines = 2
        })
    }


}
