package com.freedom.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
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

    // Read the existing main-screen checkbox without changing its storage key.
    private var accountBrowseStepPending = false
    private var accountBrowsePauseReason = ""
    private var accountBrowseNextStepAt = 0L

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

    private val accountBrowseRunnable = Runnable {
        accountBrowseStepPending = false
        performAccountBrowseStep()
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

        // A persisted run request is restored only after Android has rebound this service.
        if (MonitorStore.isRunning(this)) {
            if (!Settings.canDrawOverlays(this)) {
                MonitorStore.setRunning(this, false)
                MonitorKeepAliveService.stop(this)
                MonitorStore.addLog(this, "恢复监听失败：悬浮窗授权已失效，请重新授权后启动")
            } else {
                if (MonitorStore.isOverlayEnabled(this)) {
                    MonitorOverlay.show(this)
                }
                if (MonitorStore.isKeepAliveEnabled(this) && MonitorStore.isOverlayEnabled(this)) {
                    // Android 15 background FGS starts require a visible overlay for the SYSTEM_ALERT_WINDOW exemption.
                    // The overlay is attached above before attempting to restore the foreground companion.
                    mainHandler.postDelayed({
                        if (MonitorStore.isRunning(this) &&
                            MonitorStore.isKeepAliveEnabled(this) &&
                            MonitorStore.isOverlayEnabled(this) &&
                            Settings.canDrawOverlays(this)) {
                            MonitorKeepAliveService.start(this)
                        }
                    }, 500L)
                }
            }
        }

        if (MonitorStore.isRunning(this) && isAccountBrowseModeEnabled()) {
            scheduleAccountBrowseStep(INITIAL_BROWSE_DELAY_MS)
        }
        refreshViews()
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
        if (!Settings.canDrawOverlays(this)) {
            MonitorStore.setRunning(this, false)
            MonitorStore.addLog(this, "监听已暂停：悬浮窗授权已撤销")
            MonitorKeepAliveService.stop(this)
            cancelAccountBrowseStep()
            refreshViews()
            return
        }

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

        val friendlyEvent = friendlyEventDescription(
            eventType = event.eventType,
            className = className,
            content = content,
            description = description
        )
        val technicalDetails = buildList {
            add("原始事件名=$rawEventName")
            add("事件类型值=${event.eventType}")
            add("来源应用=$eventPackage")
            if (className.isNotBlank()) add("原始控件类名=$className")
            if (content.isNotBlank()) add("事件文本=$content")
            if (description.isNotBlank()) add("内容描述=$description")
        }.joinToString("；")
        addLog("$friendlyEvent$LOG_DETAIL_SEPARATOR$technicalDetails")

        // Scan once shortly after the latest event, not once for every event in a burst.
        mainHandler.removeCallbacks(scanRunnable)
        scanQueued = true
        mainHandler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)

        if (isAccountBrowseModeEnabled()) {
            scheduleAccountBrowseStep()
        } else {
            cancelAccountBrowseStep()
        }
    }

    /**
     * The checkbox already present on MainActivity is the user's explicit opt-in.
     * Keep its existing SharedPreferences file/key so no UI migration is needed.
     */
    private fun isAccountBrowseModeEnabled(): Boolean =
        getSharedPreferences(ACCOUNT_BROWSE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(ACCOUNT_BROWSE_KEY, false)

    private fun scheduleAccountBrowseStep(delayMs: Long = EVENT_BROWSE_DELAY_MS) {
        if (!MonitorStore.isRunning(this) || !isAccountBrowseModeEnabled()) {
            cancelAccountBrowseStep()
            return
        }
        if (accountBrowseStepPending) return

        val dwellRemaining = (accountBrowseNextStepAt - System.currentTimeMillis())
            .coerceAtLeast(0L)
        val actualDelay = maxOf(delayMs, dwellRemaining)

        accountBrowseStepPending = true
        mainHandler.postDelayed(accountBrowseRunnable, actualDelay)
    }

    private fun cancelAccountBrowseStep() {
        mainHandler.removeCallbacks(accountBrowseRunnable)
        accountBrowseStepPending = false
        accountBrowseNextStepAt = 0L
    }

    private fun pauseAccountBrowse(reason: String) {
        mainHandler.removeCallbacks(accountBrowseRunnable)
        accountBrowseStepPending = false
        accountBrowseNextStepAt = 0L

        if (accountBrowsePauseReason != reason) {
            accountBrowsePauseReason = reason
            addLog("动态浏览已暂停：$reason")
        }
    }

    /**
     * Phase 1 is deliberately read-only:
     * - operate only while the target app is the foreground window;
     * - scroll a clearly identifiable, large vertical list using its accessibility action;
     * - wait six seconds between successful page advances;
     * - never click feed cards, likes, follows, comments, or message controls.
     */
    private fun performAccountBrowseStep() {
        if (!MonitorStore.isRunning(this) || !isAccountBrowseModeEnabled()) {
            cancelAccountBrowseStep()
            return
        }

        val targetPackage = MonitorStore.targetPackage(this)
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            pauseAccountBrowse("暂时无法读取当前页面；请回到陌陌动态列表后继续")
            return
        }

        val activePackage = runCatching {
            root.packageName?.toString().orEmpty()
        }.getOrDefault("")

        if (activePackage != targetPackage) {
            runCatching { root.recycle() }
            pauseAccountBrowse("当前没有打开目标应用；请回到陌陌动态列表后继续")
            return
        }

        val scrollNode = try {
            findScrollableFeedContainer(root)
        } finally {
            runCatching { root.recycle() }
        }

        if (scrollNode == null) {
            pauseAccountBrowse("没有识别到合适的动态列表；请先打开陌陌动态信息流页面")
            return
        }

        // Set the dwell deadline before performing the action so that the resulting
        // scroll events cannot schedule another step too early.
        accountBrowseNextStepAt = System.currentTimeMillis() + BROWSE_DWELL_MS

        val scrolled = try {
            scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { scrollNode.recycle() }
        }

        if (scrolled) {
            accountBrowsePauseReason = ""
            addLog("动态浏览：已向上浏览一屏，停留 ${BROWSE_DWELL_MS / 1000} 秒后再继续")
            scheduleAccountBrowseStep(BROWSE_DWELL_MS)
        } else {
            pauseAccountBrowse("当前列表无法继续向下滚动，自动浏览已暂停")
        }
    }

    /**
     * Finds the largest visible, vertical, scrollable list in the target window.
     * A visible bottom text-entry field is treated as a chat/composer screen and
     * blocks automatic scrolling to avoid moving a conversation by mistake.
     */
    private fun findScrollableFeedContainer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val minimumWidth = (screenWidth * MIN_LIST_WIDTH_RATIO).toInt()
        val minimumHeight = (screenHeight * MIN_LIST_HEIGHT_RATIO).toInt()
        val minimumComposerWidth = (screenWidth * MIN_COMPOSER_WIDTH_RATIO).toInt()

        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var hasBottomTextEntry = false
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MonitorStore.MAX_NODES_PER_SCAN) {
                val node = queue.removeFirst()
                visited++

                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }

                    if (node.isVisibleToUser && node.isEditable &&
                        bounds.bottom >= (screenHeight * COMPOSER_BOTTOM_RATIO).toInt() &&
                        bounds.width() >= minimumComposerWidth
                    ) {
                        hasBottomTextEntry = true
                    }

                    val className = node.className?.toString().orEmpty()
                    val isKnownVerticalList =
                        className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") ||
                        className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") ||
                        className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")

                    if (node.isVisibleToUser && node.isScrollable && isKnownVerticalList) {
                        val width = bounds.width()
                        val height = bounds.height()
                        if (width >= minimumWidth && height >= minimumHeight) {
                            val area = width.toLong() * height.toLong()
                            if (area > bestArea) {
                                runCatching { bestNode?.recycle() }
                                bestNode = AccessibilityNodeInfo.obtain(node)
                                bestArea = area
                            }
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= MonitorStore.MAX_NODES_PER_SCAN) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
        }

        if (hasBottomTextEntry) {
            runCatching { bestNode?.recycle() }
            return null
        }
        return bestNode
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

    /** Convert technical accessibility events into a plain-language event summary. */
    private fun friendlyEventDescription(
        eventType: Int,
        className: String,
        content: String,
        description: String
    ): String {
        val rawClass = className.substringAfterLast('.')
        val area = when (rawClass) {
            "RecyclerView" -> "内容列表"
            "ScrollView", "HorizontalScrollView" -> "可滚动区域"
            "EditText" -> "文字输入框"
            "TextView" -> "文字区域"
            "Button", "ImageButton" -> "按钮区域"
            "ImageView" -> "图片区域"
            "WebView" -> "网页内容区域"
            "ViewPager", "ViewPager2" -> "分页区域"
            "CheckBox", "RadioButton", "Switch" -> "选项控件"
            "" -> "当前界面"
            else -> "界面控件"
        }

        val title: String
        val explanation: String
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                title = "页面内容更新"
                explanation = "这个区域的内容或结构发生了变化，可能是列表刷新、内容加载或项目更新。"
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                title = if (rawClass == "RecyclerView") "列表滚动" else "页面滚动"
                explanation = if (rawClass == "RecyclerView") {
                    "检测到内容列表的滚动位置或可见项目发生变化。"
                } else {
                    "检测到可滚动区域的位置或可见内容发生变化。"
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                title = "控件点击"
                explanation = "系统报告这个界面控件发生了一次点击事件。"
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                title = "控件长按"
                explanation = "系统报告这个界面控件发生了一次长按事件。"
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                title = "文字内容变化"
                explanation = "一个文字控件中的内容发生了变化。"
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                title = "页面或窗口切换"
                explanation = "当前应用的窗口或页面状态发生了变化。"
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                title = "选项状态变化"
                explanation = "一个界面控件报告了选中状态变化。"
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                title = "控件获得焦点"
                explanation = "输入焦点或键盘焦点移动到了这个控件。"
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                title = "文字选区变化"
                explanation = "文字控件中的选中范围发生了变化。"
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                title = "通知内容变化"
                explanation = "系统报告目标应用的通知状态发生了变化。"
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                title = "应用窗口变化"
                explanation = "系统报告当前应用相关窗口发生了变化。"
            }
            else -> {
                title = eventLabel(eventType)
                explanation = "系统检测到一个界面辅助事件；详细类型可在技术详情中查看。"
            }
        }

        val relatedText = sequenceOf(content, description)
            .firstOrNull { it.isNotBlank() }
            ?.take(100)

        return buildString {
            append(title)
            append("\n操作区域：")
            append(area)
            append("\n说明：")
            append(explanation)
            if (!relatedText.isNullOrBlank()) {
                append("\n相关文字：")
                append(relatedText)
            }
        }
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
        cancelAccountBrowseStep()
        addLog("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        scanQueued = false
        accountBrowseStepPending = false
        if (targetReceiverRegistered) {
            runCatching { unregisterReceiver(targetChangedReceiver) }
            targetReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 350L

        private const val ACCOUNT_BROWSE_PREFS = "freedom_main_ui"
        private const val ACCOUNT_BROWSE_KEY = "keep_account_mode_enabled"
        private const val INITIAL_BROWSE_DELAY_MS = 1_500L
        private const val EVENT_BROWSE_DELAY_MS = 1_200L
        private const val BROWSE_DWELL_MS = 6_000L
        private const val MIN_LIST_WIDTH_RATIO = 0.58f
        private const val MIN_LIST_HEIGHT_RATIO = 0.28f
        private const val MIN_COMPOSER_WIDTH_RATIO = 0.35f
        private const val COMPOSER_BOTTOM_RATIO = 0.62f

        private const val LOG_DETAIL_SEPARATOR = "\u001F"
    }
}
