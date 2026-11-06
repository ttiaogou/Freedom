package com.freedom.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore

/** Draggable, non-focusable live monitor panel shown above other applications. */
object MonitorOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var statusView: TextView? = null
    private var appView: TextView? = null
    private var pageView: TextView? = null
    private var eventView: TextView? = null
    private var nodeView: TextView? = null
    private var contentContainer: LinearLayout? = null
    private var collapseButton: Button? = null
    private var collapsed = false
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false

    fun show(context: Context): Boolean {
        if (attached) {
            update(context)
            return true
        }
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(context)) return false

        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        windowManager = wm

        val outer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(appContext, 12), dp(appContext, 10), dp(appContext, 12), dp(appContext, 10))
            background = roundedBackground(0xF5FFFFFF.toInt(), dp(appContext, 14).toFloat())
            elevation = dp(appContext, 8).toFloat()
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(appContext, 8))
        }
        val title = TextView(appContext).apply {
            text = "实时事件监控"
            textSize = 15f
            setTextColor(Color.rgb(24, 30, 44))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val collapseControl = Button(appContext).apply {
            text = "收起"
            textSize = 12f
            isAllCaps = false
            minHeight = dp(appContext, 34)
            minimumHeight = dp(appContext, 34)
            setPadding(dp(appContext, 8), 0, dp(appContext, 8), 0)
            setOnClickListener {
                collapsed = !collapsed
                contentContainer?.visibility = if (collapsed) View.GONE else View.VISIBLE
                text = if (collapsed) "展开" else "收起"
            }
        }
        collapseButton = collapseControl
        val stop = Button(appContext).apply {
            text = "停止"
            textSize = 12f
            isAllCaps = false
            minHeight = dp(appContext, 34)
            minimumHeight = dp(appContext, 34)
            setPadding(dp(appContext, 10), 0, dp(appContext, 10), 0)
            setOnClickListener {
                MonitorStore.setRunning(appContext, false)
                MonitorStore.addLog(appContext, timestamp() + "  已通过悬浮窗停止监听")
                appContext.sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(appContext.packageName))
                hide()
            }
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(appContext, 38), 1f))
        header.addView(collapseControl, LinearLayout.LayoutParams(-2, dp(appContext, 38)))
        header.addView(stop, LinearLayout.LayoutParams(-2, dp(appContext, 38)))
        outer.addView(header)

        val status = sectionText(appContext, 12f, 0xFF315EFB.toInt())
        val app = sectionText(appContext, 12f, 0xFF202938.toInt())
        val page = sectionText(appContext, 12f, 0xFF202938.toInt())
        val events = sectionText(appContext, 11f, 0xFF283244.toInt()).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val nodes = sectionText(appContext, 10f, 0xFF374151.toInt()).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
        statusView = status
        appView = app
        pageView = page
        eventView = events
        nodeView = nodes

        outer.addView(status)
        outer.addView(divider(appContext))
        outer.addView(label(appContext, "当前应用"))
        outer.addView(app)
        outer.addView(label(appContext, "当前页面"))
        outer.addView(page)
        outer.addView(divider(appContext))

        val content = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label(appContext, "实时事件（最近 12 条）"))
        content.addView(events)
        content.addView(divider(appContext))
        content.addView(label(appContext, "节点扫描预览"))
        content.addView(nodes)
        val scroll = ScrollView(appContext).apply {
            isFillViewport = false
            addView(content)
        }
        val contentWrapper = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, dp(appContext, 390)))
        }
        contentContainer = contentWrapper
        outer.addView(contentWrapper, LinearLayout.LayoutParams(-1, dp(appContext, 390)))

        // Drag the panel from its header without stealing touches from the scroll area.
        var downRawX = 0f
        var downRawY = 0f
        var initialX = 0
        var initialY = 0
        header.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - downRawX).toInt()
                    params.y = initialY - (event.rawY - downRawY).toInt()
                    runCatching { wm.updateViewLayout(outer, params) }
                    true
                }
                else -> true
            }
        }

        val windowType = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            (appContext.resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtLeast(dp(appContext, 260)),
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
            false
        }
    }

    fun update(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (!attached) return@post
            val snapshot = MonitorStore.snapshot(appContext)
            statusView?.text = "服务状态：${if (MonitorStore.isRunning(appContext)) "运行中" else "已停止"}  ·  扫描节点：${snapshot.visitedCount}"
            appView?.text = snapshot.app
            pageView?.text = snapshot.page
            eventView?.text = MonitorStore.logs(appContext).takeLast(12).joinToString("\n").ifBlank { "等待目标应用产生事件……" }
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
            if (attached && view != null) runCatching { windowManager?.removeView(view) }
            attached = false
            rootView = null
            statusView = null
            appView = null
            pageView = null
            eventView = null
            nodeView = null
            contentContainer = null
            collapseButton = null
            collapsed = false
            layoutParams = null
            windowManager = null
        }
    }

    private fun sectionText(context: Context, size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        setPadding(dp(context, 2), dp(context, 3), dp(context, 2), dp(context, 5))
        setTextIsSelectable(true)
    }

    private fun label(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 11f
        setTextColor(0xFF687386.toInt())
        setPadding(dp(context, 2), dp(context, 7), dp(context, 2), dp(context, 1))
    }

    private fun divider(context: Context) = View(context).apply {
        setBackgroundColor(0xFFE6EAF0.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)).apply {
            topMargin = dp(context, 5)
            bottomMargin = dp(context, 4)
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
