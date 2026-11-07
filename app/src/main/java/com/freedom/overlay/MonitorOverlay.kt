package com.freedom.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.data.MonitorActions
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.data.MonitorStore
import com.freedom.stability.MonitorKeepAliveService

/** Compact, draggable and manually resizable live monitor panel. */
object MonitorOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var appView: TextView? = null
    private var pageView: TextView? = null
    private var eventLabelView: TextView? = null
    private var eventView: TextView? = null
    private var technicalDetailsButton: Button? = null
    private var showTechnicalDetails = false
    private var nodeView: TextView? = null
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var collapseButton: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var attached = false
    private var expanded = false
    private var touchPassthrough = false

    fun show(context: Context): Boolean {
        if (attached) {
            update(context)
            return true
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(context)) {
            return false
        }

        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        windowManager = wm
        expanded = false
        showTechnicalDetails = false

        val outer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(appContext, 10), dp(appContext, 8), dp(appContext, 10), dp(appContext, 8))
            background = roundedBackground(0xF5FFFFFF.toInt(), dp(appContext, 12).toFloat())
            elevation = dp(appContext, 8).toFloat()
        }

        // Header: title is also the drag handle.
        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(appContext, 4))
        }

        val title = TextView(appContext).apply {
            text = "实时事件监控"
            textSize = 14f
            setTextColor(0xFF182235.toInt())
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }

        val collapse = Button(appContext).apply {
            text = "展开"
            textSize = 11f
            isAllCaps = false
            minHeight = dp(appContext, 30)
            minimumHeight = dp(appContext, 30)
            setPadding(dp(appContext, 5), 0, dp(appContext, 5), 0)
            setOnClickListener { setExpanded(appContext, !expanded) }
        }
        collapseButton = collapse

        val stop = Button(appContext).apply {
            text = "停止"
            textSize = 11f
            isAllCaps = false
            minHeight = dp(appContext, 30)
            minimumHeight = dp(appContext, 30)
            setPadding(dp(appContext, 6), 0, dp(appContext, 6), 0)
            setOnClickListener {
                MonitorStore.setRunning(appContext, false)
                MonitorStore.addLog(appContext, timestamp() + "  已通过悬浮窗停止监听")
                MonitorKeepAliveService.stop(appContext)
                appContext.sendBroadcast(
                    Intent(MonitorActions.ACTION_REFRESH).setPackage(appContext.packageName)
                )
                hide()
            }
        }

        header.addView(title, LinearLayout.LayoutParams(0, dp(appContext, 32), 1f))
        header.addView(collapse, LinearLayout.LayoutParams(-2, dp(appContext, 32)))
        header.addView(stop, LinearLayout.LayoutParams(-2, dp(appContext, 32)))
        outer.addView(header)

        val status = sectionText(appContext, 11f, 0xFF315EFB.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val app = sectionText(appContext, 12f, 0xFF202938.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val page = sectionText(appContext, 12f, 0xFF202938.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status
        appView = app
        pageView = page

        outer.addView(status)
        outer.addView(divider(appContext))
        outer.addView(label(appContext, "当前应用"))
        outer.addView(app)
        outer.addView(label(appContext, "当前页面"))
        outer.addView(page)
        outer.addView(divider(appContext))

        // Expanded-only content: event log and node snapshot scroll independently of the header.
        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(appContext, 3))
        }

        val eventLabel = label(appContext, "实时事件（最近 12 条）")
        eventLabelView = eventLabel

        val technicalToggle = Button(appContext).apply {
            text = "查看技术详情"
            textSize = 10f
            isAllCaps = false
            minHeight = dp(appContext, 28)
            minimumHeight = dp(appContext, 28)
            setPadding(dp(appContext, 5), 0, dp(appContext, 5), 0)
            setOnClickListener {
                showTechnicalDetails = !showTechnicalDetails
                text = if (showTechnicalDetails) "收起技术详情" else "查看技术详情"
                update(appContext)
            }
        }
        technicalDetailsButton = technicalToggle

        val eventHeader = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(eventLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(technicalToggle, LinearLayout.LayoutParams(-2, dp(appContext, 30)))
        }

        val events = sectionText(appContext, 10f, 0xFF283244.toInt()).apply {
            typeface = Typeface.DEFAULT
        }
        eventView = events
        content.addView(eventHeader)
        content.addView(events)
        content.addView(divider(appContext))
        content.addView(label(appContext, "节点扫描预览"))

        val nodes = sectionText(appContext, 9f, 0xFF374151.toInt()).apply {
            typeface = Typeface.MONOSPACE
        }
        nodeView = nodes
        content.addView(nodes)

        val scroll = ScrollView(appContext).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(content)
        }
        scrollView = scroll

        val resizeHandle = TextView(appContext).apply {
            text = "拖动此处调整大小  ↘"
            textSize = 10f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(0xFF657187.toInt())
            setPadding(0, dp(appContext, 3), dp(appContext, 3), 0)
            isClickable = true
        }

        val expandedContent = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(resizeHandle, LinearLayout.LayoutParams(-1, dp(appContext, 25)))
        }
        contentContainer = expandedContent
        outer.addView(expandedContent, LinearLayout.LayoutParams(-1, 0, 1f))

        val screenWidthPx = appContext.resources.displayMetrics.widthPixels
        val screenHeightPx = appContext.resources.displayMetrics.heightPixels
        val density = appContext.resources.displayMetrics.density
        val screenWidthDp = (screenWidthPx / density).toInt()
        val screenHeightDp = (screenHeightPx / density).toInt()

        val minWidthDp = 220
        val maxWidthDp = minOf((screenWidthDp - 24).coerceAtLeast(minWidthDp), 320)
        val defaultWidthDp = (screenWidthDp * 0.78f).toInt().coerceIn(minWidthDp, maxWidthDp)
        val savedWidthDp = MonitorStore.overlayWidthDp(appContext)
        val panelWidthDp = (if (savedWidthDp > 0) savedWidthDp else defaultWidthDp)
            .coerceIn(minWidthDp, maxWidthDp)

        val minHeightDp = 220
        val maxHeightDp = (screenHeightDp * 0.75f).toInt().coerceAtLeast(minHeightDp)
        val defaultHeightDp = (screenHeightDp * 0.34f).toInt().coerceIn(minHeightDp, maxHeightDp)
        val savedHeightDp = MonitorStore.overlayHeightDp(appContext)
        val panelHeightDp = (if (savedHeightDp > 0) savedHeightDp else defaultHeightDp)
            .coerceIn(minHeightDp, maxHeightDp)

        val windowType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val baseOverlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val overlayFlags = if (touchPassthrough) {
            baseOverlayFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            baseOverlayFlags
        }
        outer.visibility = if (touchPassthrough) View.INVISIBLE else View.VISIBLE

        val params = WindowManager.LayoutParams(
            dp(appContext, panelWidthDp),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            overlayFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(appContext, 12)
            y = dp(appContext, 72)
        }

        // Drag the panel from its header.
        var dragDownX = 0f
        var dragDownY = 0f
        var dragInitialX = 0
        var dragInitialY = 0
        header.setOnTouchListener { _, event ->
            val currentParams = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.rawX
                    dragDownY = event.rawY
                    dragInitialX = currentParams.x
                    dragInitialY = currentParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    currentParams.x = dragInitialX - (event.rawX - dragDownX).toInt()
                    currentParams.y = dragInitialY - (event.rawY - dragDownY).toInt()
                    runCatching { wm.updateViewLayout(outer, currentParams) }
                    true
                }
                else -> true
            }
        }

        // Resize by dragging the lower-right resize grip.
        var resizeDownX = 0f
        var resizeDownY = 0f
        var resizeInitialWidth = 0
        var resizeInitialHeight = 0
        var resizeInitialX = 0
        var resizeInitialY = 0
        resizeHandle.setOnTouchListener { _, event ->
            val currentParams = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeDownX = event.rawX
                    resizeDownY = event.rawY
                    resizeInitialWidth = currentParams.width
                    resizeInitialHeight = currentParams.height
                    resizeInitialX = currentParams.x
                    resizeInitialY = currentParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = (resizeInitialWidth + (event.rawX - resizeDownX).toInt())
                        .coerceIn(dp(appContext, minWidthDp), dp(appContext, maxWidthDp))
                    val newHeight = (resizeInitialHeight + (event.rawY - resizeDownY).toInt())
                        .coerceIn(dp(appContext, minHeightDp), dp(appContext, maxHeightDp))
                    val widthDelta = newWidth - resizeInitialWidth
                    val heightDelta = newHeight - resizeInitialHeight
                    currentParams.width = newWidth
                    currentParams.height = newHeight
                    // Keep the opposite (upper-left) corner fixed while the lower-right grip follows the finger.
                    currentParams.x = resizeInitialX - widthDelta
                    currentParams.y = resizeInitialY - heightDelta
                    runCatching { wm.updateViewLayout(outer, currentParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    MonitorStore.setOverlayWidthDp(
                        appContext,
                        (currentParams.width / density).toInt()
                    )
                    MonitorStore.setOverlayHeightDp(
                        appContext,
                        (currentParams.height / density).toInt()
                    )
                    true
                }
                else -> true
            }
        }

        rootView = outer
        layoutParams = params

        return try {
            wm.addView(outer, params)
            attached = true
            update(appContext)
            true
        } catch (_: Exception) {
            attached = false
            rootView = null
            layoutParams = null
            windowManager = null
            false
        }
    }

    private fun setExpanded(context: Context, value: Boolean) {
        if (!attached) return
        expanded = value
        contentContainer?.visibility = if (expanded) View.VISIBLE else View.GONE
        collapseButton?.text = if (expanded) "收起" else "展开"

        val params = layoutParams ?: return
        if (expanded) {
            val density = context.resources.displayMetrics.density
            val screenHeightDp = (context.resources.displayMetrics.heightPixels / density).toInt()
            val maxHeightDp = (screenHeightDp * 0.75f).toInt().coerceAtLeast(220)
            val defaultHeightDp = (screenHeightDp * 0.34f).toInt().coerceIn(220, maxHeightDp)
            val savedHeight = MonitorStore.overlayHeightDp(context)
            val heightDp = (if (savedHeight > 0) savedHeight else defaultHeightDp)
                .coerceIn(220, maxHeightDp)
            params.height = dp(context, heightDp)
        } else {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        val view = rootView ?: return
        runCatching {
            windowManager?.updateViewLayout(view, params)
        }
    }

    /**
     * Temporarily removes the monitor panel from the touch path while an accessibility
     * gesture is dispatched. The root view is made INVISIBLE as well as NOT_TOUCHABLE;
     * this matters on Android 12+, where opaque TYPE_APPLICATION_OVERLAY windows can
     * otherwise block pass-through touches even when FLAG_NOT_TOUCHABLE is set.
     * Must be called on the main thread.
     */
    fun setTouchPassthrough(enabled: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (touchPassthrough == enabled) return true

        val view = rootView
        val params = layoutParams
        if (!attached || view == null || params == null) {
            touchPassthrough = enabled
            return true
        }

        val oldFlags = params.flags
        val oldVisibility = view.visibility
        return try {
            if (enabled) {
                view.visibility = View.INVISIBLE
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                view.visibility = View.VISIBLE
            }
            val wm = windowManager ?: throw IllegalStateException("WindowManager is unavailable")
            wm.updateViewLayout(view, params)
            touchPassthrough = enabled
            true
        } catch (_: Exception) {
            params.flags = oldFlags
            view.visibility = oldVisibility
            runCatching { windowManager?.updateViewLayout(view, params) }
            false
        }
    }

    /** Diagnostic geometry used to verify whether the monitor panel overlaps a gesture. */
    fun touchStateDescription(): String {
        val view = rootView ?: return "实时监控悬浮窗未附加"
        val location = IntArray(2)
        runCatching { view.getLocationOnScreen(location) }
        val width = runCatching { view.width }.getOrDefault(0)
        val height = runCatching { view.height }.getOrDefault(0)
        val visible = view.visibility == View.VISIBLE
        return "悬浮窗区域=(${location[0]},${location[1]},${location[0] + width},${location[1] + height})，" +
            "可见=$visible，触摸穿透=$touchPassthrough"
    }

    fun update(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (!attached) return@post

            val snapshot = MonitorStore.snapshot(appContext)
            val accessibilityEnabled = isAccessibilityEnabled(appContext)
            val overlayAllowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(appContext)
            val serviceState = when {
                !accessibilityEnabled -> "异常（无障碍未授权）"
                !overlayAllowed -> "异常（悬浮窗未授权）"
                MonitorStore.isRunning(appContext) -> "运行中"
                else -> "已停止"
            }
            statusView?.text = "服务状态：$serviceState"
            appView?.text = snapshot.app
            pageView?.text = snapshot.page

            val showLogs = MonitorStore.isFloatingLogEnabled(appContext)
            eventLabelView?.visibility = if (showLogs) View.VISIBLE else View.GONE
            technicalDetailsButton?.visibility = if (showLogs) View.VISIBLE else View.GONE
            eventView?.visibility = if (showLogs) View.VISIBLE else View.GONE
            technicalDetailsButton?.text =
                if (showTechnicalDetails) "收起技术详情" else "查看技术详情"
            eventView?.text = formatLogLines(
                MonitorStore.logs(appContext).takeLast(12),
                showTechnicalDetails
            ).ifBlank { "等待目标应用产生事件……" }

            nodeView?.text = buildString {
                if (snapshot.nodes.isEmpty()) {
                    append("尚无节点快照。进入目标应用并触发页面变化后，将显示系统公开的无障碍节点。")
                } else {
                    append(snapshot.nodes.joinToString("\n"))
                    append("\n\n已访问 ${snapshot.visitedCount} 个节点；预览 ${snapshot.nodes.size} 个")
                    if (snapshot.truncated) append("；达到单次扫描上限，结果可能不完整")
                }
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val view = rootView
            if (attached && view != null) {
                runCatching { windowManager?.removeView(view) }
            }
            attached = false
            expanded = false
            touchPassthrough = false
            rootView = null
            statusView = null
            appView = null
            pageView = null
            eventLabelView = null
            eventView = null
            technicalDetailsButton = null
            showTechnicalDetails = false
            nodeView = null
            contentContainer = null
            scrollView = null
            collapseButton = null
            layoutParams = null
            windowManager = null
        }
    }


    private fun formatLogLines(lines: List<String>, includeTechnicalDetails: Boolean): String =
        lines.joinToString("\n") { line -> formatLogLine(line, includeTechnicalDetails) }

    private fun formatLogLine(line: String, includeTechnicalDetails: Boolean): String {
        val separatorIndex = line.indexOf(LOG_DETAIL_SEPARATOR)
        if (separatorIndex >= 0) {
            val summary = line.substring(0, separatorIndex).trimEnd()
            val technical = line.substring(separatorIndex + 1).trim()
            return if (includeTechnicalDetails && technical.isNotBlank()) {
                "$summary\n  技术详情：$technical"
            } else {
                summary
            }
        }
        return if (includeTechnicalDetails) line else formatLegacyEventLine(line)
    }

    /** Convert previously stored v1 event strings too, so old history remains readable after upgrade. */
    private fun formatLegacyEventLine(line: String): String {
        val type = Regex("\\[(TYPE_[A-Z0-9_]+)\\]").find(line)?.groupValues?.get(1)
            ?: return line
        val className = Regex("原始类名=([^；]+)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val simpleClass = className.substringAfterLast('.')
        val area = when (simpleClass) {
            "RecyclerView" -> "内容列表"
            "ScrollView", "HorizontalScrollView" -> "可滚动区域"
            "EditText" -> "文字输入框"
            "TextView" -> "文字区域"
            "Button", "ImageButton" -> "按钮区域"
            "ImageView" -> "图片区域"
            "WebView" -> "网页内容区域"
            "ViewPager", "ViewPager2" -> "分页区域"
            "CheckBox", "RadioButton", "Switch" -> "选项控件"
            else -> "界面区域"
        }
        val title = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "页面内容更新"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") "列表滚动" else "页面滚动"
            "TYPE_VIEW_CLICKED" -> "控件点击"
            "TYPE_VIEW_LONG_CLICKED" -> "控件长按"
            "TYPE_VIEW_TEXT_CHANGED" -> "文字内容变化"
            "TYPE_WINDOW_STATE_CHANGED" -> "页面或窗口切换"
            "TYPE_VIEW_SELECTED" -> "选项状态变化"
            "TYPE_VIEW_FOCUSED" -> "控件获得焦点"
            "TYPE_VIEW_TEXT_SELECTION_CHANGED" -> "文字选区变化"
            "TYPE_NOTIFICATION_STATE_CHANGED" -> "通知内容变化"
            "TYPE_WINDOWS_CHANGED" -> "应用窗口变化"
            else -> "界面事件"
        }
        val explanation = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "这个区域的内容或结构发生了变化，可能是列表刷新、内容加载或项目更新。"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") "检测到内容列表的滚动位置或可见项目发生变化。" else "检测到可滚动区域的位置或可见内容发生变化。"
            "TYPE_VIEW_CLICKED" -> "系统报告这个界面控件发生了一次点击事件。"
            "TYPE_VIEW_LONG_CLICKED" -> "系统报告这个界面控件发生了一次长按事件。"
            "TYPE_VIEW_TEXT_CHANGED" -> "一个文字控件中的内容发生了变化。"
            "TYPE_WINDOW_STATE_CHANGED" -> "当前应用的窗口或页面状态发生了变化。"
            else -> "系统检测到界面状态变化；详细类型可在技术详情中查看。"
        }
        val time = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}").find(line)?.value.orEmpty()
        val relatedText = Regex("(?:内容|描述)=([^；]+)").find(line)?.groupValues?.get(1)?.trim()
        return buildString {
            if (time.isNotBlank()) append(time).append("  ")
            append(title)
            append("\n操作区域：").append(area)
            append("\n说明：").append(explanation)
            if (!relatedText.isNullOrBlank()) append("\n相关文字：").append(relatedText.take(100))
        }
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

    private fun sectionText(context: Context, size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 3))
        setTextIsSelectable(true)
    }

    private fun label(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 10f
        setTextColor(0xFF687386.toInt())
        setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 1))
    }

    private fun divider(context: Context) = View(context).apply {
        setBackgroundColor(0xFFE6EAF0.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)).apply {
            topMargin = dp(context, 3)
            bottomMargin = dp(context, 3)
        }
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(1, 0xFFDCE2EA.toInt())
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun timestamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    private const val LOG_DETAIL_SEPARATOR = "\u001F"
}
