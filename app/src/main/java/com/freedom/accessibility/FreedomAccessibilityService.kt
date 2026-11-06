package com.freedom.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
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
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class FreedomAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEventSignature = ""
    private var lastEventAt = 0L
    private var scanQueued = false
    private var targetReceiverRegistered = false

    // Read the existing main-screen checkbox without changing its storage key.
    private enum class AccountBrowsePhase {
        IDLE,
        WAIT_FOR_LIST_SCROLL,
        WAIT_FOR_SCROLL_SETTLE,
        WAIT_FOR_DETAIL_LOAD,
        DETAIL_DWELL,
        WAIT_FOR_LIST_RETURN,
        PAUSED
    }

    private data class PageFingerprint(
        val signature: String,
        val nodeCount: Int
    )

    private data class ScrollGestureProfile(
        val durationMs: Long,
        val curveOffsetRatio: Float,
        val accelerationExponent: Float
    )

    private var accountBrowsePhase = AccountBrowsePhase.IDLE
    private var accountBrowseStepPending = false
    private var accountBrowsePauseReason = ""
    private var accountBrowseDeadlineAt = 0L
    private var accountBrowseSourceFingerprint = ""
    private var accountBrowseDetailFingerprint = ""
    private var accountBrowseLastStableFingerprint = ""
    private var accountBrowseStableSamples = 0
    private var accountBrowseTouchActive = false
    private var accountBrowseGestureInFlight = false
    private var accountBrowseScrollEventSeen = false
    private var accountBrowseFallbackScrollAttempted = false
    private var refreshReceiverRegistered = false

    private val targetChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyTargetPackageFilter()
            resetAccountBrowseSession()
            addLog("监听目标已更新：${MonitorStore.targetPackage(this@FreedomAccessibilityService)}")
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!MonitorStore.isRunning(this@FreedomAccessibilityService) ||
                !isAccountBrowseModeEnabled()
            ) {
                resetAccountBrowseSession()
            } else if (accountBrowsePhase == AccountBrowsePhase.IDLE) {
                startAccountBrowseIfNeeded()
            }
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

    // Resume after the user finishes navigating or interacting with the target app.
    private val manualTouchResumeRunnable = Runnable {
        if (!accountBrowseTouchActive && MonitorStore.isRunning(this) &&
            isAccountBrowseModeEnabled()
        ) {
            accountBrowsePhase = AccountBrowsePhase.IDLE
            accountBrowsePauseReason = ""
            accountBrowseSourceFingerprint = ""
            accountBrowseDetailFingerprint = ""
            startAccountBrowseIfNeeded()
        }
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
        if (!refreshReceiverRegistered) {
            val filter = IntentFilter(MonitorActions.ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(refreshReceiver, filter)
            }
            refreshReceiverRegistered = true
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
            startAccountBrowseIfNeeded()
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
        if (event == null) return
        if (!MonitorStore.isRunning(this)) {
            resetAccountBrowseSession()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            MonitorStore.setRunning(this, false)
            MonitorStore.addLog(this, "监听已暂停：悬浮窗授权已撤销")
            MonitorKeepAliveService.stop(this)
            resetAccountBrowseSession()
            refreshViews()
            return
        }

        val targetPackage = MonitorStore.targetPackage(this)
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage != targetPackage || eventPackage == packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        ) {
            accountBrowseScrollEventSeen = true
        }

        if (!isAccountBrowseModeEnabled()) {
            resetAccountBrowseSession()
        } else {
            when (event.eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    if (accountBrowseGestureInFlight) return
                    if (!accountBrowseTouchActive) {
                        accountBrowseTouchActive = true
                        mainHandler.removeCallbacks(manualTouchResumeRunnable)
                        pauseAccountBrowse("检测到手动触摸；自动浏览暂时让出操作权")
                    }
                    return
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    if (accountBrowseGestureInFlight) return
                    if (accountBrowseTouchActive) {
                        accountBrowseTouchActive = false
                        addLog("手动触摸已结束；页面安静一段时间后自动恢复浏览")
                        mainHandler.removeCallbacks(manualTouchResumeRunnable)
                        mainHandler.postDelayed(manualTouchResumeRunnable, MANUAL_TOUCH_RESUME_MS)
                    }
                    return
                }
            }
            if (accountBrowsePhase == AccountBrowsePhase.IDLE) {
                startAccountBrowseIfNeeded()
            }
        }

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
    }

    /**
     * The existing checkbox is the user's explicit opt-in. Keep its original key.
     * The first cycle starts only when the selected target app is actually foregrounded.
     */
    private fun isAccountBrowseModeEnabled(): Boolean =
        getSharedPreferences(ACCOUNT_BROWSE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(ACCOUNT_BROWSE_KEY, false)

    private fun startAccountBrowseIfNeeded() {
        if (!MonitorStore.isRunning(this) || !isAccountBrowseModeEnabled()) return
        if (accountBrowsePhase != AccountBrowsePhase.IDLE) return
        if (MonitorStore.targetPackage(this) != MOMO_PACKAGE) {
            pauseAccountBrowse("当前阶段仅支持陌陌动态列表；其他应用暂未接入浏览适配")
            return
        }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val isTargetForeground = runCatching {
            root.packageName?.toString().orEmpty() == MOMO_PACKAGE
        }.getOrDefault(false)
        runCatching { root.recycle() }
        if (!isTargetForeground) return

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
        val delay = randomBrowseDelayMs()
        addLog("动态浏览已启动；首次滑动将在 ${delay / 1000.0} 秒后进行")
        scheduleAccountBrowseStep(delay)
    }

    private fun scheduleAccountBrowseStep(delayMs: Long) {
        if (!MonitorStore.isRunning(this) || !isAccountBrowseModeEnabled() ||
            accountBrowseTouchActive || accountBrowsePhase == AccountBrowsePhase.PAUSED ||
            accountBrowsePhase == AccountBrowsePhase.IDLE
        ) {
            return
        }
        if (accountBrowseStepPending) return
        accountBrowseStepPending = true
        mainHandler.postDelayed(accountBrowseRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun resetAccountBrowseSession() {
        mainHandler.removeCallbacks(accountBrowseRunnable)
        mainHandler.removeCallbacks(manualTouchResumeRunnable)
        accountBrowseStepPending = false
        accountBrowsePhase = AccountBrowsePhase.IDLE
        accountBrowsePauseReason = ""
        accountBrowseDeadlineAt = 0L
        accountBrowseSourceFingerprint = ""
        accountBrowseDetailFingerprint = ""
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        accountBrowseTouchActive = false
        accountBrowseGestureInFlight = false
        accountBrowseScrollEventSeen = false
        accountBrowseFallbackScrollAttempted = false
    }

    private fun pauseAccountBrowse(reason: String) {
        mainHandler.removeCallbacks(accountBrowseRunnable)
        accountBrowseStepPending = false
        accountBrowseGestureInFlight = false
        accountBrowsePhase = AccountBrowsePhase.PAUSED
        accountBrowseDeadlineAt = 0L
        if (accountBrowsePauseReason != reason) {
            accountBrowsePauseReason = reason
            addLog("动态浏览已暂停：$reason")
        }
    }

    private fun performAccountBrowseStep() {
        if (!MonitorStore.isRunning(this) || !isAccountBrowseModeEnabled()) {
            resetAccountBrowseSession()
            return
        }
        if (accountBrowseTouchActive || accountBrowsePhase == AccountBrowsePhase.PAUSED) return

        when (accountBrowsePhase) {
            AccountBrowsePhase.IDLE,
            AccountBrowsePhase.PAUSED -> return
            AccountBrowsePhase.WAIT_FOR_LIST_SCROLL -> performFeedScroll()
            AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE -> openVisibleFeedCard()
            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD -> checkDetailPageLoaded()
            AccountBrowsePhase.DETAIL_DWELL -> returnFromDetailPage()
            AccountBrowsePhase.WAIT_FOR_LIST_RETURN -> checkFeedListReturned()
        }
    }

    private fun obtainTargetRootOrPause(): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            pauseAccountBrowse("暂时无法读取当前页面；请确认陌陌仍在前台")
            return null
        }
        val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (activePackage != MOMO_PACKAGE) {
            runCatching { root.recycle() }
            pauseAccountBrowse("当前没有打开陌陌；自动浏览已暂停")
            return null
        }
        return root
    }

    private fun performFeedScroll() {
        val root = obtainTargetRootOrPause() ?: return
        if (!isMomoDynamicFeedPage(root)) {
            runCatching { root.recycle() }
            pauseAccountBrowse("没有确认当前位于陌陌动态列表；为避免误操作，已暂停")
            return
        }

        accountBrowseSourceFingerprint = pageFingerprint(root).signature
        val scrollNode = try {
            findScrollableFeedContainer(root)
        } finally {
            runCatching { root.recycle() }
        }
        if (scrollNode == null) {
            pauseAccountBrowse("没有识别到可滚动的动态列表；已暂停")
            return
        }

        val bounds = Rect()
        val validBounds = runCatching {
            scrollNode.getBoundsInScreen(bounds)
            bounds.width() >= MIN_GESTURE_WIDTH_PX && bounds.height() >= MIN_GESTURE_HEIGHT_PX
        }.getOrDefault(false)
        if (!validBounds) {
            runCatching { scrollNode.recycle() }
            pauseAccountBrowse("动态列表尺寸不足，无法安全执行滑动")
            return
        }

        val profile = loadScrollGestureProfile()
        val path = createSmoothScrollPath(bounds, profile)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, profile.durationMs))
            .build()

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        accountBrowseScrollEventSeen = false
        accountBrowseFallbackScrollAttempted = false
        accountBrowseGestureInFlight = true
        val accepted = runCatching {
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    accountBrowseGestureInFlight = false
                    if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ||
                        !MonitorStore.isRunning(this@FreedomAccessibilityService)
                    ) return
                    addLog("动态列表滑动完成；正在等待页面稳定")
                    scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    accountBrowseGestureInFlight = false
                    if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) {
                        pauseAccountBrowse("滑动被系统或用户操作中断；请重新启动后继续")
                    }
                }
            }, mainHandler)
        }.getOrDefault(false)

        if (!accepted) {
            accountBrowseGestureInFlight = false
            accountBrowseFallbackScrollAttempted = true
            val fallbackScrolled = runCatching {
                scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }.getOrDefault(false)
            runCatching { scrollNode.recycle() }
            if (fallbackScrolled) {
                addLog("动态列表已通过无障碍滚动动作移动；正在等待页面稳定")
                scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
            } else {
                pauseAccountBrowse("系统未接受滑动手势，备用滚动动作也未成功")
            }
            return
        }

        runCatching { scrollNode.recycle() }
        addLog("动态列表正在平滑滑动（${profile.durationMs} 毫秒）")
    }

    private fun loadScrollGestureProfile(): ScrollGestureProfile {
        val prefs = getSharedPreferences(ACCOUNT_BROWSE_PREFS, Context.MODE_PRIVATE)
        return ScrollGestureProfile(
            durationMs = prefs.getLong(KEY_BROWSE_GESTURE_DURATION_MS, DEFAULT_GESTURE_DURATION_MS)
                .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS),
            curveOffsetRatio = (prefs.getFloat(KEY_BROWSE_GESTURE_CURVE_OFFSET, DEFAULT_CURVE_OFFSET_RATIO))
                .coerceIn(0f, MAX_CURVE_OFFSET_RATIO),
            accelerationExponent = prefs.getFloat(
                KEY_BROWSE_GESTURE_ACCELERATION,
                DEFAULT_GESTURE_ACCELERATION
            ).coerceIn(MIN_GESTURE_ACCELERATION, MAX_GESTURE_ACCELERATION)
        )
    }

    /** Build a smooth, curved swipe with gradual acceleration and deceleration. */
    private fun createSmoothScrollPath(
        bounds: Rect,
        profile: ScrollGestureProfile
    ): Path {
        val path = Path()
        val centerX = bounds.centerX().toFloat()
        val startY = bounds.top + bounds.height() * SCROLL_START_FRACTION
        val endY = bounds.top + bounds.height() * SCROLL_END_FRACTION
        val curveOffset = bounds.width() * profile.curveOffsetRatio
        val steps = GESTURE_PATH_STEPS

        for (step in 0..steps) {
            val t = step.toFloat() / steps.toFloat()
            val eased = if (t < 0.5f) {
                0.5f * (2f * t).pow(profile.accelerationExponent)
            } else {
                1f - 0.5f * (2f * (1f - t)).pow(profile.accelerationExponent)
            }
            val x = centerX + sin(PI * t.toDouble()).toFloat() * curveOffset
            val y = startY + (endY - startY) * eased
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    private fun openVisibleFeedCard() {
        // dispatchGesture() can be accepted by Android without producing a real list scroll
        // (for example, when the host view ignores injected touch). Verify the scroll event;
        // if absent, retry once through the list's native accessibility scroll action.
        if (!accountBrowseScrollEventSeen && !accountBrowseFallbackScrollAttempted) {
            val fallbackRoot = obtainTargetRootOrPause() ?: return
            val fallbackNode = try {
                findScrollableFeedContainer(fallbackRoot)
            } finally {
                runCatching { fallbackRoot.recycle() }
            }
            if (fallbackNode == null) {
                pauseAccountBrowse("手势没有触发列表滚动，且未找到可用的备用滚动区域")
                return
            }
            val fallbackScrolled = try {
                fallbackNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } catch (_: Exception) {
                false
            } finally {
                runCatching { fallbackNode.recycle() }
            }
            accountBrowseFallbackScrollAttempted = true
            if (!fallbackScrolled) {
                pauseAccountBrowse("触摸滑动和备用无障碍滚动均未能移动列表")
                return
            }
            addLog("未检测到触摸滑动产生的列表滚动；已尝试系统备用滚动动作")
            scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
            return
        }

        val root = obtainTargetRootOrPause() ?: return
        if (!isMomoDynamicFeedPage(root)) {
            runCatching { root.recycle() }
            pauseAccountBrowse("滑动后页面不再是可识别的陌陌动态列表；已暂停")
            return
        }

        val scrollNode = try {
            findScrollableFeedContainer(root)
        } finally {
            runCatching { root.recycle() }
        }
        if (scrollNode == null) {
            pauseAccountBrowse("滑动后未找到动态列表；已暂停")
            return
        }

        val cardNode = try {
            findClickableFeedCard(scrollNode)
        } finally {
            runCatching { scrollNode.recycle() }
        }
        if (cardNode == null) {
            pauseAccountBrowse("没有找到安全可识别的动态卡片；没有点击任何控件")
            return
        }

        val currentRoot = obtainTargetRootOrPause()
        if (currentRoot == null) {
            runCatching { cardNode.recycle() }
            return
        }
        accountBrowseSourceFingerprint = pageFingerprint(currentRoot).signature
        runCatching { currentRoot.recycle() }

        val clicked = runCatching {
            cardNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        runCatching { cardNode.recycle() }

        if (!clicked) {
            pauseAccountBrowse("动态卡片未接受无障碍点击；已停止本轮操作")
            return
        }

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD
        accountBrowseDeadlineAt = System.currentTimeMillis() + DETAIL_LOAD_TIMEOUT_MS
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        addLog("已打开一条动态；正在确认详情页面是否成功载入")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Only direct, large, fully visible, clickable children of the feed list qualify as cards. */
    private fun findClickableFeedCard(
        scrollNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val listBounds = Rect()
        runCatching { scrollNode.getBoundsInScreen(listBounds) }
        val minWidth = (resources.displayMetrics.widthPixels * MIN_FEED_CARD_WIDTH_RATIO).toInt()
        val minHeight = (resources.displayMetrics.heightPixels * MIN_FEED_CARD_HEIGHT_RATIO).toInt()
        val childCount = runCatching { scrollNode.childCount }.getOrDefault(0)

        var bestNode: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        var bestArea = 0L
        for (index in 0 until childCount) {
            val child = runCatching { scrollNode.getChild(index) }.getOrNull() ?: continue
            try {
                val bounds = Rect()
                runCatching { child.getBoundsInScreen(bounds) }
                if (!child.isVisibleToUser || !child.isClickable || child.isEditable) continue
                if (bounds.width() < minWidth || bounds.height() < minHeight) continue
                if (bounds.top < listBounds.top || bounds.bottom > listBounds.bottom) continue

                val area = bounds.width().toLong() * bounds.height().toLong()
                if (bounds.top < bestTop || (bounds.top == bestTop && area > bestArea)) {
                    runCatching { bestNode?.recycle() }
                    bestNode = AccessibilityNodeInfo.obtain(child)
                    bestTop = bounds.top
                    bestArea = area
                }
            } finally {
                runCatching { child.recycle() }
            }
        }
        return bestNode
    }

    private fun checkDetailPageLoaded() {
        val root = obtainTargetRootOrPause() ?: return
        val fingerprint = try {
            pageFingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }

        if (fingerprint.signature != accountBrowseSourceFingerprint &&
            fingerprint.nodeCount >= MIN_DETAIL_NODE_COUNT
        ) {
            if (fingerprint.signature == accountBrowseLastStableFingerprint) {
                accountBrowseStableSamples++
            } else {
                accountBrowseLastStableFingerprint = fingerprint.signature
                accountBrowseStableSamples = 1
            }
            if (accountBrowseStableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
                accountBrowseDetailFingerprint = fingerprint.signature
                val dwell = randomBrowseDelayMs()
                accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
                addLog("动态详情已确认载入；本次阅读停留 ${dwell / 1000.0} 秒")
                scheduleAccountBrowseStep(dwell)
                return
            }
        } else {
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("等待详情页面超时，无法确认动态已经载入；没有继续执行返回操作")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun returnFromDetailPage() {
        val root = obtainTargetRootOrPause() ?: return
        val fingerprint = try {
            pageFingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }
        if (fingerprint.signature != accountBrowseDetailFingerprint ||
            fingerprint.signature == accountBrowseSourceFingerprint
        ) {
            pauseAccountBrowse("阅读期间页面结构发生变化；为避免返回到错误页面，已暂停")
            return
        }

        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            pauseAccountBrowse("系统未能执行返回操作；请手动返回动态列表后重新启动")
            return
        }
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
        accountBrowseDeadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
        addLog("阅读停留结束；正在确认已返回动态列表")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    private fun checkFeedListReturned() {
        val root = obtainTargetRootOrPause() ?: return
        val isFeedList = try {
            isMomoDynamicFeedPage(root)
        } finally {
            runCatching { root.recycle() }
        }

        if (isFeedList) {
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
            val delay = randomBrowseDelayMs()
            addLog("已返回陌陌动态列表；下一次滑动将在 ${delay / 1000.0} 秒后进行")
            scheduleAccountBrowseStep(delay)
        } else if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("返回后没有重新识别到动态列表；请检查页面并重新启动")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun isMomoDynamicFeedPage(root: AccessibilityNodeInfo): Boolean {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName != MOMO_PACKAGE || !hasVisibleMomoDynamicTab(root)) return false

        // Do not require a clickable card merely to establish that this is the feed.
        // Card lookup happens after scrolling; coupling it here could block the first swipe.
        val scrollNode = findScrollableFeedContainer(root) ?: return false
        runCatching { scrollNode.recycle() }
        return true
    }

    private fun hasVisibleMomoDynamicTab(root: AccessibilityNodeInfo): Boolean {
        // Some tab widgets mark the container selected rather than the TextView itself.
        // Carry selected state down the tree so the same visible tab is still recognized.
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to false)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val (node, selectedInPath) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    if (node.isVisibleToUser && selected &&
                        text == MOMO_DYNAMIC_TAB_LABEL && id.endsWith(MOMO_TAB_TITLE_ID_SUFFIX)
                    ) return true

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to selected)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().first.recycle() }
            }
        }
        return false
    }

    private fun pageFingerprint(root: AccessibilityNodeInfo): PageFingerprint {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)
        val structure = ArrayList<String>()
        var visibleCount = 0
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        visibleCount++
                        val className = node.className?.toString().orEmpty().substringAfterLast('.')
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        structure.add(
                            "$depth:$className:$viewId:${node.isClickable}:${node.isScrollable}:" +
                                "${bounds.width() / FINGERPRINT_SIZE_BUCKET_PX}:${bounds.height() / FINGERPRINT_SIZE_BUCKET_PX}"
                        )
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to depth + 1)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        return PageFingerprint(
            signature = "$visibleCount:${structure.joinToString("|").hashCode()}",
            nodeCount = visibleCount
        )
    }

    private fun randomBrowseDelayMs(): Long =
        Random.nextLong(MIN_BROWSE_DELAY_MS, MAX_BROWSE_DELAY_MS + 1L)

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
        pauseAccountBrowse("无障碍服务被系统中断；请检查系统设置后重新启动")
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
        if (refreshReceiverRegistered) {
            runCatching { unregisterReceiver(refreshReceiver) }
            refreshReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 350L

        private const val ACCOUNT_BROWSE_PREFS = "freedom_main_ui"
        private const val ACCOUNT_BROWSE_KEY = "keep_account_mode_enabled"
        private const val KEY_BROWSE_GESTURE_DURATION_MS = "account_browse_gesture_duration_ms"
        private const val KEY_BROWSE_GESTURE_CURVE_OFFSET = "account_browse_gesture_curve_offset"
        private const val KEY_BROWSE_GESTURE_ACCELERATION = "account_browse_gesture_acceleration"
        private const val MOMO_PACKAGE = "com.immomo.momo"
        private const val MOMO_DYNAMIC_TAB_LABEL = "动态"
        private const val MOMO_TAB_TITLE_ID_SUFFIX = "/id/tab_title"

        private const val MIN_BROWSE_DELAY_MS = 3_000L
        private const val MAX_BROWSE_DELAY_MS = 15_000L
        private const val DETAIL_LOAD_TIMEOUT_MS = 12_000L
        private const val LIST_RETURN_TIMEOUT_MS = 10_000L
        private const val PAGE_STATE_POLL_MS = 350L
        private const val SCROLL_SETTLE_MS = 850L
        private const val MANUAL_TOUCH_RESUME_MS = 2_500L
        private const val REQUIRED_STABLE_PAGE_SAMPLES = 2
        private const val MIN_DETAIL_NODE_COUNT = 12
        private const val PAGE_FINGERPRINT_MAX_NODES = 500
        private const val FINGERPRINT_SIZE_BUCKET_PX = 24

        private const val DEFAULT_GESTURE_DURATION_MS = 620L
        private const val MIN_GESTURE_DURATION_MS = 350L
        private const val MAX_GESTURE_DURATION_MS = 1_200L
        private const val DEFAULT_CURVE_OFFSET_RATIO = 0.012f
        private const val MAX_CURVE_OFFSET_RATIO = 0.04f
        private const val DEFAULT_GESTURE_ACCELERATION = 1.8f
        private const val MIN_GESTURE_ACCELERATION = 1.0f
        private const val MAX_GESTURE_ACCELERATION = 3.0f
        private const val MIN_GESTURE_WIDTH_PX = 220
        private const val MIN_GESTURE_HEIGHT_PX = 300
        private const val SCROLL_START_FRACTION = 0.78f
        private const val SCROLL_END_FRACTION = 0.32f
        private const val GESTURE_PATH_STEPS = 32
        private const val MIN_FEED_CARD_WIDTH_RATIO = 0.75f
        private const val MIN_FEED_CARD_HEIGHT_RATIO = 0.18f

        private const val MIN_LIST_WIDTH_RATIO = 0.58f
        private const val MIN_LIST_HEIGHT_RATIO = 0.28f
        private const val MIN_COMPOSER_WIDTH_RATIO = 0.35f
        private const val COMPOSER_BOTTOM_RATIO = 0.62f

        private const val LOG_DETAIL_SEPARATOR = "\u001F"
    }
}
