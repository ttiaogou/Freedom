package com.freedom.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class FreedomAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEventSignature = ""
    private var lastEventAt = 0L
    private var scanQueued = false
    private var targetReceiverRegistered = false

    private val targetChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyTargetPackageFilter()
            addLog("监听目标已更新：${MonitorStore.targetPackage(this@FreedomAccessibilityService)}")
        }
    }

    private val scanRunnable = Runnable {
        scanQueued = false
        scanActiveWindow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!targetReceiverRegistered) {
            val filter = IntentFilter(MonitorActions.ACTION_TARGET_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(targetChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(targetChangedReceiver, filter)
            }
            targetReceiverRegistered = true
        }
        applyTargetPackageFilter()
        addLog("无障碍服务已连接；当前仅接收指定目标应用的事件")
    }

    private fun applyTargetPackageFilter() {
        runCatching {
            val info = serviceInfo ?: return
            info.packageNames = arrayOf(MonitorStore.targetPackage(this))
            setServiceInfo(info)
        }.onFailure {
            addLog("更新目标应用过滤器失败：${it.javaClass.simpleName}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !MonitorStore.isRunning(this)) return

        val targetPackage = MonitorStore.targetPackage(this)
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage != targetPackage || eventPackage == packageName) return

        val rawEventName = rawEventName(event.eventType)
        val className = event.className?.toString().orEmpty()
        val content = event.text
            ?.joinToString(" ") { it?.toString().orEmpty() }
            ?.trim()
            .orEmpty()
            .take(300)
        val description = event.contentDescription?.toString()?.take(180).orEmpty()
        val signature = "$eventPackage|$rawEventName|$className|$content|$description"
        val now = System.currentTimeMillis()
        // Reduce identical event bursts while preserving meaningful changes.
        if (signature == lastEventSignature && now - lastEventAt < 250L) return
        lastEventSignature = signature
        lastEventAt = now

        val appLabel = resolveAppLabel(eventPackage)
        val pageHint = sequenceOf(content, description, className.substringAfterLast('.'))
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        MonitorStore.updateAppPage(this, app = appLabel, page = pageHint)

        val classLabel = chineseClass(className.substringAfterLast('.'))
        val details = buildList {
            if (classLabel.isNotBlank()) add("控件=$classLabel")
            if (className.isNotBlank()) add("原始类名=$className")
            if (content.isNotBlank()) add("内容=$content")
            if (description.isNotBlank()) add("描述=$description")
        }.joinToString("；")
        addLog("${eventLabel(event.eventType)} [$rawEventName]" + if (details.isNotBlank()) "：$details" else "")

        // Scan once shortly after the latest event, not once for every event in a burst.
        mainHandler.removeCallbacks(scanRunnable)
        scanQueued = true
        mainHandler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)
    }

    private fun scanActiveWindow() {
        if (!MonitorStore.isRunning(this)) return
        val targetPackage = MonitorStore.targetPackage(this)
        val root = findTargetWindowRoot(targetPackage)
        if (root == null) {
            addLog("节点扫描未完成：当前活动窗口中没有找到目标应用公开的根节点")
            return
        }

        val rootPackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")

        val appLabel = resolveAppLabel(rootPackage)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val displayLines = ArrayList<String>(MonitorStore.MAX_DISPLAY_NODES)
        var visited = 0
        var truncated = false
        var pageTitle = ""
        queue.add(root to 0)

        try {
            while (queue.isNotEmpty() && visited < MonitorStore.MAX_NODES_PER_SCAN) {
                val (node, depth) = queue.removeFirst()
                visited++
                try {
                    val fullClassName = node.className?.toString()?.takeIf(String::isNotBlank) ?: "Unknown"
                    val rawClass = fullClassName.substringAfterLast('.')
                    val label = chineseClass(rawClass)
                    val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("").trim().take(120)
                    val description = runCatching { node.contentDescription?.toString().orEmpty() }
                        .getOrDefault("").trim().take(100)
                    val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                    val state = buildList {
                        if (runCatching { node.isClickable }.getOrDefault(false)) add("可点击")
                        if (runCatching { node.isLongClickable }.getOrDefault(false)) add("可长按")
                        if (runCatching { node.isEditable }.getOrDefault(false)) add("可输入")
                        if (runCatching { node.isScrollable }.getOrDefault(false)) add("可滚动")
                        if (runCatching { node.isCheckable }.getOrDefault(false)) add("可勾选")
                        if (runCatching { node.isChecked }.getOrDefault(false)) add("已勾选")
                        if (runCatching { node.isFocused }.getOrDefault(false)) add("焦点")
                        if (runCatching { node.isEnabled }.getOrDefault(false)) add("启用")
                    }.joinToString("、")

                    if (pageTitle.isBlank() && depth <= 3) {
                        pageTitle = sequenceOf(text, description)
                            .firstOrNull { it.isNotBlank() && it.length <= 60 }
                            .orEmpty()
                    }

                    if (displayLines.size < MonitorStore.MAX_DISPLAY_NODES) {
                        val indent = "  ".repeat(depth.coerceAtMost(10))
                        val readable = listOf(text, description).filter(String::isNotBlank).distinct().joinToString("；")
                        val line = buildString {
                            append(indent)
                            append(label)
                            append(" <")
                            append(fullClassName)
                            append(">")
                            if (readable.isNotBlank()) append("：$readable")
                            if (viewId.isNotBlank()) append(" [ID:$viewId]")
                            if (state.isNotBlank()) append(" {$state}")
                        }
                        displayLines.add(line)
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= MonitorStore.MAX_NODES_PER_SCAN) {
                            truncated = true
                            break
                        }
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to depth + 1)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
            if (queue.isNotEmpty() || visited >= MonitorStore.MAX_NODES_PER_SCAN) truncated = true
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().first.recycle() }
            }
        }

        if (pageTitle.isBlank()) pageTitle = MonitorStore.snapshot(this).page
        MonitorStore.saveSnapshot(
            context = this,
            app = appLabel,
            page = pageTitle.ifBlank { "未识别页面标题" },
            nodes = displayLines,
            visitedCount = visited,
            truncated = truncated
        )
        addLog("节点扫描完成：访问 $visited 个节点，预览显示 ${displayLines.size} 个" + if (truncated) "；已达到扫描上限，结果可能不完整" else "")
        refreshViews()
    }


    /** Prefer the active root, then search visible interactive windows for the target package. */
    private fun findTargetWindowRoot(targetPackage: String): AccessibilityNodeInfo? {
        val active = runCatching { rootInActiveWindow }.getOrNull()
        if (active != null) {
            val activePackage = runCatching { active.packageName?.toString().orEmpty() }.getOrDefault("")
            if (activePackage == targetPackage) return active
            runCatching { active.recycle() }
        }

        val visibleWindows = runCatching { windows }.getOrNull().orEmpty()
        for (window in visibleWindows) {
            val candidate = runCatching { window.root }.getOrNull() ?: continue
            val candidatePackage = runCatching { candidate.packageName?.toString().orEmpty() }.getOrDefault("")
            if (candidatePackage == targetPackage) return candidate
            runCatching { candidate.recycle() }
        }
        return null
    }

    private fun resolveAppLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        MonitorStore.addLog(this, "$time  $message")
        refreshViews()
    }

    private fun refreshViews() {
        sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(packageName))
        MonitorOverlay.update(this)
    }

    private fun rawEventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "TYPE_VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "TYPE_VIEW_HOVER_ENTER"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "TYPE_VIEW_HOVER_EXIT"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "TYPE_TOUCH_EXPLORATION_GESTURE_START"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "TYPE_TOUCH_EXPLORATION_GESTURE_END"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TYPE_VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "TYPE_ANNOUNCEMENT"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "TYPE_VIEW_ACCESSIBILITY_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "TYPE_GESTURE_DETECTION_START"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "TYPE_GESTURE_DETECTION_END"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TYPE_TOUCH_INTERACTION_START"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TYPE_TOUCH_INTERACTION_END"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "TYPE_VIEW_CONTEXT_CLICKED"
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "TYPE_ASSIST_READING_CONTEXT"
        else -> "ACCESSIBILITY_EVENT_$type"
    }

    private fun eventLabel(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "控件被点击"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "控件被长按"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "控件被选中"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "控件获得焦点"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "控件文字发生变化"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "页面或窗口发生切换"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "窗口内容发生变化"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "页面发生滚动"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "文字选区发生变化"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "文字按粒度移动"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "指针进入控件"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "指针离开控件"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "触摸探索手势开始"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "触摸探索手势结束"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "手势检测开始"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "手势检测结束"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "触摸交互开始"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "触摸交互结束"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "控件获得无障碍焦点"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "控件失去无障碍焦点"
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "控件上下文点击"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "系统播报内容"
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "辅助阅读上下文变化"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "系统窗口发生变化"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "通知状态发生变化"
        else -> "无障碍事件"
    }

    private fun chineseClass(rawClass: String): String = when (rawClass) {
        "FrameLayout" -> "帧布局容器"
        "LinearLayout" -> "线性布局"
        "RelativeLayout" -> "相对布局"
        "ConstraintLayout" -> "约束布局"
        "TextView" -> "文本控件"
        "EditText" -> "文本输入框"
        "ImageView" -> "图片控件"
        "ImageButton" -> "图片按钮"
        "Button" -> "按钮"
        "CheckBox" -> "复选框"
        "RadioButton" -> "单选按钮"
        "Switch" -> "开关控件"
        "RecyclerView" -> "列表控件"
        "ScrollView" -> "滚动容器"
        "ViewPager" -> "分页容器"
        "ViewPager2" -> "分页容器"
        "WebView" -> "网页容器"
        "View" -> "普通视图"
        "Unknown" -> "未知控件"
        else -> "$rawClass 控件"
    }

    override fun onInterrupt() {
        addLog("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        scanQueued = false
        if (targetReceiverRegistered) {
            runCatching { unregisterReceiver(targetChangedReceiver) }
            targetReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 350L
    }
}
