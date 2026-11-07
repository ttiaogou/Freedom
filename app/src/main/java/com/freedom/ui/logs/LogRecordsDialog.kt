package com.freedom.ui.logs

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Presents recent event logs while leaving storage and export operations with MainActivity. */
internal object LogRecordsDialog {
    fun show(
        activity: Activity,
        records: List<String>,
        dp: (Int) -> Int,
        onExport: (List<String>) -> Unit,
        onCleared: () -> Unit
    ) {
        var showTechnicalDetails = false
        val view = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xFF263244.toInt())
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = LogTextFormatter.formatLogLines(records, false).ifBlank { "暂无运行记录" }
        }
        val detailsToggle = Button(activity).apply {
            text = "查看技术详情"
            isAllCaps = false
            setOnClickListener {
                showTechnicalDetails = !showTechnicalDetails
                text = if (showTechnicalDetails) "收起技术详情" else "查看技术详情"
                view.text = LogTextFormatter.formatLogLines(records, showTechnicalDetails)
                    .ifBlank { "暂无运行记录" }
            }
        }
        val scroll = ScrollView(activity).apply {
            addView(view)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(detailsToggle, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        }
        AlertDialog.Builder(activity)
            .setTitle("运行记录（最近 ${records.size} 条）")
            .setView(container)
            .setPositiveButton("关闭", null)
            .setNeutralButton("导出 TXT") { _, _ -> onExport(records) }
            .setNegativeButton("清空记录") { _, _ -> onCleared() }
            .show()
    }
}
