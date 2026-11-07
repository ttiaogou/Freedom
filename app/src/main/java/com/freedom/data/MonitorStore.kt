package com.freedom.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Base64
import org.json.JSONArray
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

/** Local-only storage for monitor state, recent events and the latest node snapshot. */
object MonitorStore {

    const val DEFAULT_TARGET = "com.immomo.momo"
    const val MAX_NODES_PER_SCAN = 3000
    const val MAX_DISPLAY_NODES = 140

    private const val PREFS = "freedom_monitor_store"
    private const val KEY_TARGET = "target_package"
    private const val KEY_RUNNING = "monitor_running"
    // Read only for one-time migration from the previous SharedPreferences log store.
    private const val KEY_LOGS = "event_logs"
    private const val KEY_APP = "current_app"
    private const val KEY_PAGE = "current_page"
    private const val KEY_NODES = "latest_nodes"
    private const val KEY_NODE_COUNT = "latest_node_count"
    private const val KEY_NODE_TRUNCATED = "latest_node_truncated"
    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
    private const val KEY_BOOT_CHECK = "boot_check_enabled"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_FLOATING_LOG = "floating_log_enabled"
    private const val KEY_OVERLAY_WIDTH_DP = "overlay_width_dp"
    private const val KEY_OVERLAY_HEIGHT_DP = "overlay_height_dp"

    private const val MAX_LOGS = 300
    private const val MAX_LOG_LINE_LENGTH = 700
    private const val LOG_FILE_NAME = "freedom_event_logs.log"
    private const val LOG_FLUSH_DELAY_MS = 350L
    private const val LOG_FLUSH_RETRY_MS = 1_500L

    private val logLock = Any()
    private val logWriterThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HandlerThread("FreedomLogWriter", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    }
    private val logWriter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(logWriterThread.looper)
    }
    private val logCache = ArrayDeque<String>()

    @Volatile
    private var logsLoaded = false
    private var logContext: Context? = null
    private var logDirty = false
    private var logFlushScheduled = false
    private var logGeneration = 0L
    private var legacyMigrationPending = false

    private val flushLogsRunnable = Runnable { flushLogCache() }

    data class Snapshot(
        val app: String,
        val page: String,
        val nodes: List<String>,
        val visitedCount: Int,
        val truncated: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun targetPackage(context: Context): String =
        prefs(context).getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET

    @Synchronized
    fun setTargetPackage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TARGET, value.trim()).apply()
    }

    /** Persisted user intent: whether monitoring should resume after a system rebind. */
    @Synchronized
    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    @Synchronized
    fun setRunning(context: Context, running: Boolean) {
        // This flag is the user's resume intent; commit it before leaving the start/stop action.
        prefs(context).edit().putBoolean(KEY_RUNNING, running).commit()
    }

    @Synchronized
    fun isKeepAliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_ALIVE, true)

    @Synchronized
    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply()
    }

    @Synchronized
    fun isBootCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOT_CHECK, true)

    @Synchronized
    fun setBootCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT_CHECK, enabled).apply()
    }

    @Synchronized
    fun isOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY_ENABLED, true)

    @Synchronized
    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
    }

    @Synchronized
    fun isFloatingLogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_LOG, true)

    @Synchronized
    fun setFloatingLogEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_LOG, enabled).apply()
    }

    @Synchronized
    fun overlayWidthDp(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_WIDTH_DP, 0)

    @Synchronized
    fun setOverlayWidthDp(context: Context, widthDp: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_WIDTH_DP, widthDp.coerceAtLeast(220)).apply()
    }

    @Synchronized
    fun overlayHeightDp(context: Context): Int =
        prefs(context).getInt(KEY_OVERLAY_HEIGHT_DP, 0)

    @Synchronized
    fun setOverlayHeightDp(context: Context, heightDp: Int) {
        prefs(context).edit().putInt(KEY_OVERLAY_HEIGHT_DP, heightDp.coerceAtLeast(200)).apply()
    }

    /**
     * Add to a bounded in-memory ring and persist asynchronously in batches.
     * The writer rewrites only the latest MAX_LOGS entries, avoiding a SharedPreferences
     * JSON parse/serialize and disk write for every AccessibilityEvent.
     */
    fun addLog(context: Context, line: String) {
        ensureLogsLoaded(context)
        synchronized(logLock) {
            logCache.addLast(line.take(MAX_LOG_LINE_LENGTH))
            while (logCache.size > MAX_LOGS) logCache.removeFirst()
            logDirty = true
            scheduleLogFlushLocked(LOG_FLUSH_DELAY_MS)
        }
    }

    /** Returns the current in-memory view, including records not yet flushed to disk. */
    fun logs(context: Context): List<String> {
        ensureLogsLoaded(context)
        return synchronized(logLock) { logCache.toList() }
    }

    fun clearLogs(context: Context) {
        ensureLogsLoaded(context)
        val appContext = context.applicationContext
        val generation: Long
        synchronized(logLock) {
            logCache.clear()
            logDirty = false
            logGeneration++
            generation = logGeneration
            legacyMigrationPending = false
            logWriter.removeCallbacks(flushLogsRunnable)
            logFlushScheduled = false
        }
        // The single writer thread serializes deletion with any earlier flush request.
        logWriter.post {
            val stillCurrent = synchronized(logLock) { generation == logGeneration }
            if (stillCurrent) {
                runCatching { File(appContext.filesDir, LOG_FILE_NAME).delete() }
                prefs(appContext).edit().remove(KEY_LOGS).apply()
            }
        }
    }

    /** Request an immediate background flush, for lifecycle boundaries such as service destroy. */
    fun flushPendingLogs(context: Context) {
        ensureLogsLoaded(context)
        synchronized(logLock) {
            if (!logDirty) return
            scheduleLogFlushLocked(0L)
        }
    }

    private fun ensureLogsLoaded(context: Context) {
        if (logsLoaded) return
        synchronized(logLock) {
            if (logsLoaded) return
            val appContext = context.applicationContext
            logContext = appContext
            val logFile = File(appContext.filesDir, LOG_FILE_NAME)
            val storedLogs = if (logFile.exists() && logFile.length() > 0L) {
                readLogFile(logFile)
            } else {
                readLegacyPreferenceLogs(appContext)
            }
            storedLogs.takeLast(MAX_LOGS).forEach(logCache::addLast)
            legacyMigrationPending = !(logFile.exists() && logFile.length() > 0L) &&
                storedLogs.isNotEmpty()
            logsLoaded = true

            if (legacyMigrationPending) {
                logDirty = true
                scheduleLogFlushLocked(LOG_FLUSH_DELAY_MS)
            }
        }
    }

    private fun readLogFile(file: File): List<String> = runCatching {
        file.readLines(StandardCharsets.UTF_8)
            .mapNotNull { encoded ->
                if (encoded.isBlank()) null
                else runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                }.getOrNull()
            }
    }.getOrDefault(emptyList())

    private fun readLegacyPreferenceLogs(context: Context): List<String> = runCatching {
        val raw = prefs(context).getString(KEY_LOGS, "[]")
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf(String::isNotBlank)
        }
    }.getOrDefault(emptyList())

    private fun scheduleLogFlushLocked(delayMs: Long) {
        if (logFlushScheduled) return
        logFlushScheduled = true
        logWriter.postDelayed(flushLogsRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun flushLogCache() {
        val appContext: Context
        val snapshot: List<String>
        val generation: Long
        synchronized(logLock) {
            logFlushScheduled = false
            if (!logDirty) return
            appContext = logContext ?: return
            snapshot = logCache.toList()
            generation = logGeneration
            logDirty = false
        }

        val success = writeLogFileSnapshot(appContext, snapshot, generation)
        synchronized(logLock) {
            if (!success && generation == logGeneration) {
                logDirty = true
                scheduleLogFlushLocked(LOG_FLUSH_RETRY_MS)
            } else if (success && generation == logGeneration && legacyMigrationPending) {
                // Remove the old preference only after the replacement file is safely written.
                prefs(appContext).edit().remove(KEY_LOGS).apply()
                legacyMigrationPending = false
            }
        }
    }

    private fun writeLogFileSnapshot(
        context: Context,
        records: List<String>,
        generation: Long
    ): Boolean {
        val target = File(context.filesDir, LOG_FILE_NAME)
        val temporary = File(context.filesDir, "$LOG_FILE_NAME.tmp")
        return try {
            temporary.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                records.takeLast(MAX_LOGS).forEach { record ->
                    writer.write(Base64.encodeToString(record.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    writer.newLine()
                }
            }

            synchronized(logLock) {
                if (generation != logGeneration) {
                    temporary.delete()
                    return true
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
            true
        } catch (_: Exception) {
            runCatching { temporary.delete() }
            false
        }
    }

    /**
     * Persist page metadata only when the displayed values actually change.
     * A package change also invalidates the old node preview, as before.
     */
    @Synchronized
    fun updateAppPage(context: Context, app: String? = null, page: String? = null): Boolean {
        val p = prefs(context)
        val previousApp = p.getString(KEY_APP, "")
        val previousPage = p.getString(KEY_PAGE, "")
        val nextApp = app?.takeIf(String::isNotBlank)?.take(100)
        val nextPage = page?.takeIf(String::isNotBlank)?.take(160)
        val appChanged = nextApp != null && previousApp != nextApp
        val pageChanged = nextPage != null && previousPage != nextPage
        if (!appChanged && !pageChanged) return false

        val editor = p.edit()
        if (appChanged) {
            editor.putString(KEY_APP, nextApp)
            if (!previousApp.isNullOrBlank()) {
                editor.putString(KEY_NODES, "[]")
                    .putInt(KEY_NODE_COUNT, 0)
                    .putBoolean(KEY_NODE_TRUNCATED, false)
            }
        }
        if (pageChanged) editor.putString(KEY_PAGE, nextPage)
        editor.apply()
        return true
    }

    /**
     * Save a tree snapshot only when at least one persisted field changed.
     * Returns false for an identical snapshot so callers can skip redundant UI refreshes.
     */
    @Synchronized
    fun saveSnapshot(
        context: Context,
        app: String,
        page: String,
        nodes: List<String>,
        visitedCount: Int,
        truncated: Boolean
    ): Boolean {
        val safeApp = app.take(100)
        val safePage = page.take(160)
        val safeNodes = nodes.take(MAX_DISPLAY_NODES)
        val nodeArray = JSONArray().apply { safeNodes.forEach { put(it) } }
        val nodeJson = nodeArray.toString()
        val p = prefs(context)

        val changed =
            p.getString(KEY_APP, "尚未识别") != safeApp ||
            p.getString(KEY_PAGE, "等待页面信息") != safePage ||
            p.getString(KEY_NODES, "[]") != nodeJson ||
            p.getInt(KEY_NODE_COUNT, 0) != visitedCount ||
            p.getBoolean(KEY_NODE_TRUNCATED, false) != truncated

        if (!changed) return false
        p.edit()
            .putString(KEY_APP, safeApp)
            .putString(KEY_PAGE, safePage)
            .putString(KEY_NODES, nodeJson)
            .putInt(KEY_NODE_COUNT, visitedCount)
            .putBoolean(KEY_NODE_TRUNCATED, truncated)
            .apply()
        return true
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
