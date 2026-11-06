package com.freedom.data

import android.content.Context
import org.json.JSONArray

/**
 * 本地监控数据存储
 * 保存运行状态、事件记录、当前应用、页面和节点信息。
 */
object MonitorStore {

    const val DEFAULT_TARGET = "com.immomo.momo"
    const val MAX_NODES_PER_SCAN = 3000
    const val MAX_DISPLAY_NODES = 140

    private const val PREFS = "freedom_monitor_store"
    private const val KEY_TARGET = "target_package"
    private const val KEY_RUNNING = "monitor_running"
    private const val KEY_LOGS = "event_logs"
    private const val KEY_APP = "current_app"
    private const val KEY_PAGE = "current_page"
    private const val KEY_NODES = "latest_nodes"
    private const val KEY_NODE_COUNT = "latest_node_count"
    private const val KEY_NODE_TRUNCATED = "latest_node_truncated"
    private const val MAX_LOGS = 300

    data class Snapshot(
        val app: String,
        val page: String,
        val nodes: List<String>,
        val visitedCount: Int,
        val truncated: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun targetPackage(context: Context): String =
        prefs(context).getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET

    @Synchronized
    fun setTargetPackage(context: Context, value: String) {
        prefs(context)
            .edit()
            .putString(KEY_TARGET, value.trim())
            .apply()
    }

    @Synchronized
    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    @Synchronized
    fun setRunning(context: Context, running: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_RUNNING, running)
            .apply()
    }

    @Synchronized
    fun addLog(context: Context, line: String) {
        val p = prefs(context)

        val current = runCatching {
            JSONArray(p.getString(KEY_LOGS, "[]"))
        }.getOrElse {
            JSONArray()
        }

        current.put(line.take(700))

        val trimmed = JSONArray()
        val start = (current.length() - MAX_LOGS).coerceAtLeast(0)

        for (index in start until current.length()) {
            trimmed.put(current.optString(index))
        }

        p.edit()
            .putString(KEY_LOGS, trimmed.toString())
            .apply()
    }

    @Synchronized
    fun logs(context: Context): List<String> {
        val array = runCatching {
            JSONArray(prefs(context).getString(KEY_LOGS, "[]"))
        }.getOrElse {
            JSONArray()
        }

        return (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }
    }

    @Synchronized
    fun clearLogs(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_LOGS)
            .apply()
    }

    @Synchronized
    fun updateAppPage(
        context: Context,
        app: String? = null,
        page: String? = null
    ) {
        val p = prefs(context)
        val previousApp = p.getString(KEY_APP, "")
        val editor = p.edit()

        if (!app.isNullOrBlank()) {
            editor.putString(KEY_APP, app.take(100))

            if (!previousApp.isNullOrBlank() && previousApp != app) {
                editor.putString(KEY_NODES, "[]")
                    .putInt(KEY_NODE_COUNT, 0)
                    .putBoolean(KEY_NODE_TRUNCATED, false)
            }
        }

        if (!page.isNullOrBlank()) {
            editor.putString(KEY_PAGE, page.take(160))
        }

        editor.apply()
    }

    @Synchronized
    fun saveSnapshot(
        context: Context,
        app: String,
        page: String,
        nodes: List<String>,
        visitedCount: Int,
        truncated: Boolean
    ) {
        val array = JSONArray()

        nodes.take(MAX_DISPLAY_NODES).forEach {
            array.put(it)
        }

        prefs(context)
            .edit()
            .putString(KEY_APP, app.take(100))
            .putString(KEY_PAGE, page.take(160))
            .putString(KEY_NODES, array.toString())
            .putInt(KEY_NODE_COUNT, visitedCount)
            .putBoolean(KEY_NODE_TRUNCATED, truncated)
            .apply()
    }

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)

        val array = runCatching {
            JSONArray(p.getString(KEY_NODES, "[]"))
        }.getOrElse {
            JSONArray()
        }

        val nodes = (0 until array.length()).mapNotNull {
            array.optString(it).takeIf(String::isNotBlank)
        }

        return Snapshot(
            app = p.getString(KEY_APP, "尚未识别") ?: "尚未识别",
            page = p.getString(KEY_PAGE, "等待页面信息") ?: "等待页面信息",
            nodes = nodes,
            visitedCount = p.getInt(KEY_NODE_COUNT, 0),
            truncated = p.getBoolean(KEY_NODE_TRUNCATED, false)
        )
    }
}