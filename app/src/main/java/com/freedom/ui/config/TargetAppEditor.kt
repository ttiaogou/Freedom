package com.freedom.ui.config

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore

/** Edits the selected target package while preserving the existing validation and save flow. */
internal object TargetAppEditor {
    fun show(
        activity: Activity,
        dp: (Int) -> Int,
        packagePattern: Regex,
        timestamp: () -> String,
        refreshScreen: () -> Unit
    ) {
        val input = EditText(activity).apply {
            setSingleLine(true)
            hint = "例如 com.immomo.momo"
            setText(MonitorStore.targetPackage(activity))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(TextView(activity).apply {
                text = "输入需要监控的 Android 应用包名："
                textSize = 13f
                setTextColor(0xFF596579.toInt())
            })
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle("监控目标")
            .setView(container)
            .setPositiveButton("保存目标") { _, _ ->
                val value = input.text.toString().trim()
                if (!packagePattern.matches(value)) {
                    Toast.makeText(
                        activity,
                        "包名格式不正确，例如 com.immomo.momo",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    MonitorStore.setTargetPackage(activity, value)
                    activity.sendBroadcast(
                        Intent(MonitorActions.ACTION_TARGET_CHANGED).setPackage(activity.packageName)
                    )
                    MonitorStore.updateAppPage(
                        activity,
                        app = "尚未识别",
                        page = "等待页面信息"
                    )
                    MonitorStore.addLog(activity, timestamp() + "  监控目标已设置为 $value")
                    refreshScreen()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
