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
    private var nodeView: TextView? = null
    private var contentContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var collapseButton: Button? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var attached = false
    private var expanded = false

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

        val events = sectionText(appContext, 10f, 0xFF283244.toInt()).apply {
            typeface = Typeface.MONOSPACE
        }
        eventView = events
        content.addView(eventLabel)
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

        val params = WindowManager.LayoutParams(
            dp(appContext, panelWidthDp),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            eventView?.visibility = if (showLogs) View.VISIBLE else View.GONE
            eventView?.text = MonitorStore.logs(appContext)
                .takeLast(12)
                .joinToString("\n")
                .ifBlank { "等待目标应用产生事件……" }

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
            rootView = null
            statusView = null
            appView = null
            pageView = null
            eventLabelView = null
            eventView = null
            nodeView = null
            contentContainer = null
            scrollView = null
            collapseButton = null
            layoutParams = null
            windowManager = null
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
}
