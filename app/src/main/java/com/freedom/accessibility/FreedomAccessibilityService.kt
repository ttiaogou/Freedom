package com.freedom.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.accessibility.browse.MomoDynamicCardSelector
import com.freedom.overlay.GestureTrailOverlay
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
        WAIT_FOR_DYNAMIC_TAB,
        VERIFY_NAVIGATION_ACTION,
        WAIT_FOR_FEED_STABLE,
        FIRST_SWIPE_COUNTDOWN,
        WAIT_FOR_LIST_SCROLL,
        WAIT_FOR_SCROLL_SETTLE,
        WAIT_FOR_CARD_OPEN,
        WAIT_FOR_SAFE_CARD,
        WAIT_FOR_DETAIL_LOAD,
        DETAIL_DWELL,
        WAIT_FOR_IMAGE_BROWSER_RETURN,
        WAIT_FOR_DETAIL_RESTABILIZE,
        WAIT_FOR_LIST_RETURN,
        PAUSED
    }

    private data class PageFingerprint(
        val signature: String,
        val nodeCount: Int
    )

    private data class FeedReadinessInspection(
        val fingerprint: PageFingerprint?,
        val reason: String
    )

    private data class TabLabelEvidence(
        val label: String,
        val bounds: Rect,
        val selected: Boolean
    )

    private data class DynamicTabRowEvidence(
        val contentTop: Int,
        val diagnostic: String,
        val dynamicBounds: Rect,
        val dynamicSelected: Boolean,
        val selectionKnown: Boolean
    )

    private data class HomeBottomNavEvidence(
        val homeBounds: Rect,
        val homeSelected: Boolean,
        val selectionKnown: Boolean,
        val diagnostic: String
    )

    private data class ManualResumeContext(
        val phaseName: String,
        val activityClass: String,
        val sourceFingerprint: String,
        val detailFingerprint: String,
        val imageBrowserActive: Boolean,
        val lastProgrammaticAction: String,
        val capturedAt: Long
    )

    private data class DynamicTabInspection(
        val evidence: DynamicTabRowEvidence?,
        val reason: String
    )

    private data class FeedGestureTarget(
        val bounds: Rect,
        val className: String
    )

    private data class ScrollGestureProfile(
        val durationMs: Long,
        val curveOffsetRatio: Float,
        val microPerturbationFrequencyHz: Float
    )

    private data class ScrollGesturePlan(
        val path: Path,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )

    private var accountBrowsePhase = AccountBrowsePhase.IDLE
    private var accountBrowseStepPending = false
    private var accountBrowsePauseReason = ""
    private var accountBrowseLastDynamicTabWaitReason = ""
    private var accountBrowseDeadlineAt = 0L
    private var accountBrowseSourceFingerprint = ""
    private var accountBrowseDetailFingerprint = ""
    private var accountBrowseLastStableFingerprint = ""
    private var accountBrowseStableSamples = 0
    private var accountBrowseTouchActive = false
    private var accountBrowseManualResumeAllowed = false
    private var accountBrowseTouchStartedDuringGesture = false
    private var accountBrowseTouchEndedDuringGesture = false
    private var accountBrowseGestureInFlight = false
    private var accountBrowseScrollEventSeen = false
    private var accountBrowseScrollEventCount = 0
    private var accountBrowseLastScrollEventAt = 0L
    private var accountBrowseSwipeCount = 0
    private var accountBrowseFallbackScrollAttempted = false
    private var accountBrowseSourceSnapshot = ""
    private var accountBrowseReturnedStableSamples = 0
    private var accountBrowseLastReturnedSnapshot = ""
    private var accountBrowseReturnMismatchLogged = false
    private var accountBrowseCountdownRemaining = 0
    private var accountBrowseOverlayPassthroughActive = false
    private var accountBrowseCurrentActivityClass = ""
    private var accountBrowseImageBrowserReturnActivityClass = ""
    private var accountBrowseImageBrowserActive = false
    private var accountBrowseDetailDwellDueAt = 0L
    private var accountBrowseDetailDwellRemainingMs = 0L
    private var accountBrowseIgnoreManualEventsUntil = 0L
    private var accountBrowseProgrammaticActionLabel = ""
    private var accountBrowseSafeCardRecheckCount = 0
    private var accountBrowseLastGlobalTouchEventAt = 0L
    private var accountBrowseLastGlobalTouchEventName = ""
    private var accountBrowseLastGlobalTouchEventOrigin = ""
    private var accountBrowsePausedContext: ManualResumeContext? = null
    private var accountBrowseNavigationActionKey = ""
    private var accountBrowseNavigationBeforeActivity = ""
    private var accountBrowseNavigationBeforeFingerprint = ""
    private var accountBrowseNavigationActionDeadlineAt = 0L
    private var accountBrowseNavigationActionAttempts = 0
    private var accountBrowseRecoveryBackAttempts = 0
    private var accountBrowseRecoveryHomeClickAttempts = 0
    private var accountBrowseRecoveryDynamicClickAttempts = 0
    private var accountBrowseDynamicTabClickConfirmed = false
    private var accountBrowseNotTargetForegroundLogged = false
    private var accountBrowseRootUnavailableLogged = false

    // Device-only gesture calibration state. Calibration strokes are restricted to
    // Freedom's own calibration surface and never target the selected third-party app.
    private var gestureCalibrationReceiverRegistered = false
    private var gestureCalibrationInProgress = false
    private var gestureCalibrationTrialIndex = 0
    private var gestureCalibrationBounds: Rect? = null

    private val gestureCalibrationRunnable = Runnable {
        dispatchNextGestureCalibrationTrial()
    }

    private val gestureCalibrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GestureTuning.ACTION_START_CALIBRATION -> startGestureCalibration(intent)
                GestureTuning.ACTION_CANCEL_CALIBRATION -> {
                    cancelGestureCalibration("用户关闭了校准页面")
                }
            }
        }
    }

    private val monitorOverlayPassthroughWatchdog = Runnable {
        if (accountBrowseOverlayPassthroughActive) {
            restoreMonitorOverlayTouchPassthrough()
            if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) {
                pauseAccountBrowse("滑动回调超时；已恢复实时监控悬浮窗触摸状态")
            }
        }
    }
    private var firstSwipeCountdownWindow: WindowManager? = null
    private var firstSwipeCountdownView: TextView? = null
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
        when {
            !accountBrowseManualResumeAllowed -> Unit
            accountBrowseTouchActive -> addLog("手动恢复检查未执行：仍检测到屏幕触摸，继续等待用户操作结束")
            !MonitorStore.isRunning(this) -> addLog("手动恢复检查未执行：监听已停止")
            !isAccountBrowseModeEnabled() -> addLog("手动恢复检查未执行：自动浏览模式已关闭")
            else -> {
                accountBrowseManualResumeAllowed = false
                val context = accountBrowsePausedContext
                addLog(
                    "用户最后一次屏幕操作后已静默 ${MANUAL_TOUCH_RESUME_MS / 1000.0} 秒；" +
                        "开始基于暂停上下文重新分析当前页面；暂停前阶段=${context?.phaseName ?: "未知"}，" +
                        "暂停前 Activity=${context?.activityClass?.ifBlank { "未知" } ?: "未知"}"
                )
                accountBrowsePauseReason = ""
                accountBrowseSourceFingerprint = ""
                accountBrowseDetailFingerprint = ""
                accountBrowseLastStableFingerprint = ""
                accountBrowseStableSamples = 0
                accountBrowseNavigationActionKey = ""
                accountBrowseNavigationBeforeActivity = ""
                accountBrowseNavigationBeforeFingerprint = ""
                accountBrowseNavigationActionDeadlineAt = 0L
                accountBrowseNavigationActionAttempts = 0
                accountBrowseRecoveryBackAttempts = 0
                accountBrowseRecoveryHomeClickAttempts = 0
                accountBrowseRecoveryDynamicClickAttempts = 0
                accountBrowseDynamicTabClickConfirmed = false
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
                accountBrowseLastDynamicTabWaitReason = ""
                addLog("手动恢复已进入主动导航：先识别页面层级，再返回必要的上级页面，主动定位并验证‘动态’标签")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
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
        if (!gestureCalibrationReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(GestureTuning.ACTION_START_CALIBRATION)
                addAction(GestureTuning.ACTION_CANCEL_CALIBRATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    gestureCalibrationReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(gestureCalibrationReceiver, filter)
            }
            gestureCalibrationReceiverRegistered = true
        }
        applyTargetPackageFilter()
        addLog("无障碍服务已连接；普通应用事件仅处理指定目标应用，系统触摸事件用于识别用户操作")

        // A persisted run request is restored only after Android has rebound this service.
        // Never restore monitoring if the user has not explicitly selected a run mode.
        if (MonitorStore.isRunning(this) && !isAccountBrowseModeEnabled()) {
            MonitorStore.setRunning(this, false)
            MonitorKeepAliveService.stop(this)
            MonitorStore.addLog(
                this,
                "恢复监听失败：没有选择运行模式；请先选择模式后重新启动"
            )
        }
        if (MonitorStore.isRunning(this)) {
            if (!Settings.canDrawOverlays(this)) {
                MonitorStore.setRunning(this, false)
                MonitorKeepAliveService.stop(this)
                MonitorStore.addLog(this, "恢复监听失败：悬浮窗授权已失效，请重新授权后启动")
            } else {
                if (MonitorStore.isOverlayEnabled(this) && MonitorStore.isFloatingLogEnabled(this)) {
                    MonitorOverlay.show(this)
                }
                if (MonitorStore.isKeepAliveEnabled(this) && MonitorStore.isOverlayEnabled(this) &&
                    MonitorStore.isFloatingLogEnabled(this)
                ) {
                    // Android 15 background FGS starts require a visible overlay for the SYSTEM_ALERT_WINDOW exemption.
                    // The overlay is attached above before attempting to restore the foreground companion.
                    mainHandler.postDelayed({
                        if (MonitorStore.isRunning(this) &&
                            MonitorStore.isKeepAliveEnabled(this) &&
                            MonitorStore.isOverlayEnabled(this) &&
                            MonitorStore.isFloatingLogEnabled(this) &&
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
            // System-generated TOUCH_INTERACTION events have no view-tree source package.
            // A package whitelist would discard them before onAccessibilityEvent is called.
            // Ordinary app events remain restricted to the selected target below.
            info.packageNames = null
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
        val eventType = event.eventType

        // These two events are emitted by Android itself, not populated from a view tree.
        // Handle them before checking event.packageName; it may be null or not Momo's package.
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        ) {
            if (!isAccountBrowseModeEnabled()) {
                resetAccountBrowseSession()
                return
            }

            when (eventType) {
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    accountBrowseLastGlobalTouchEventAt = System.currentTimeMillis()
                    accountBrowseLastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_START"
                    accountBrowseLastGlobalTouchEventOrigin = if (accountBrowseGestureInFlight) {
                        "Freedom 自动滑动期间收到；具体来源待确认"
                    } else {
                        "全局触摸事件；系统未提供来源应用"
                    }
                    if (accountBrowseGestureInFlight) {
                        accountBrowseTouchStartedDuringGesture = true
                        addLog(
                            "收到系统触摸开始事件；发生在 Freedom 自动滑动执行期间，归类为自动手势期间的触摸/中断候选；" +
                                "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；阶段=${accountBrowsePhase.name}；" +
                                "${currentMonitorPageContext()}；系统未提供该事件的手指坐标"
                        )
                        return
                    }
                    addLog(
                        "用户在操作屏幕：收到 TYPE_TOUCH_INTERACTION_START；" +
                            "来源应用=${eventPackage.ifBlank { "系统未提供来源应用" }}；" +
                            "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；" +
                            "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；系统未提供手指坐标"
                    )
                    if (!accountBrowseTouchActive) {
                        accountBrowseTouchActive = true
                        mainHandler.removeCallbacks(manualTouchResumeRunnable)
                        if (accountBrowsePhase != AccountBrowsePhase.PAUSED) {
                            pauseAccountBrowse(
                                "检测到手动触摸；自动浏览暂时让出操作权",
                                allowManualResume = true
                            )
                        } else {
                            accountBrowseManualResumeAllowed = true
                            if (accountBrowsePausedContext == null) {
                                accountBrowsePausedContext = ManualResumeContext(
                                    phaseName = "PAUSED",
                                    activityClass = accountBrowseCurrentActivityClass,
                                    sourceFingerprint = accountBrowseSourceFingerprint,
                                    detailFingerprint = accountBrowseDetailFingerprint,
                                    imageBrowserActive = accountBrowseImageBrowserActive,
                                    lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                                    capturedAt = System.currentTimeMillis()
                                )
                            }
                            addLog("检测到用户正在操作屏幕；原暂停原因=${accountBrowsePauseReason.ifBlank { "未记录" }}；操作结束后重新分析页面层级并导航到动态标签")
                        }
                    }
                }

                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    accountBrowseLastGlobalTouchEventAt = System.currentTimeMillis()
                    accountBrowseLastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_END"
                    accountBrowseLastGlobalTouchEventOrigin = if (accountBrowseGestureInFlight) {
                        "Freedom 自动滑动期间收到；具体来源待确认"
                    } else {
                        "全局触摸事件；系统未提供来源应用"
                    }
                    if (accountBrowseGestureInFlight) {
                        accountBrowseTouchEndedDuringGesture = true
                        addLog(
                            "收到系统触摸结束事件；发生在 Freedom 自动滑动执行期间，归类为自动手势期间的触摸/中断候选；" +
                                "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；阶段=${accountBrowsePhase.name}；" +
                                "${currentMonitorPageContext()}；系统未提供该事件的手指坐标"
                        )
                        return
                    }
                    addLog(
                        "用户停止操作屏幕：收到 TYPE_TOUCH_INTERACTION_END；" +
                            "来源应用=${eventPackage.ifBlank { "系统未提供来源应用" }}；" +
                            "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；" +
                            "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；系统未提供手指坐标"
                    )
                    if (accountBrowseTouchActive) {
                        accountBrowseTouchActive = false
                        if (accountBrowseManualResumeAllowed) {
                            addLog("用户已停止操作屏幕；开始 ${MANUAL_TOUCH_RESUME_MS / 1000.0} 秒静默倒计时，期间新操作会重新计时")
                            mainHandler.removeCallbacks(manualTouchResumeRunnable)
                            mainHandler.postDelayed(manualTouchResumeRunnable, MANUAL_TOUCH_RESUME_MS)
                        } else {
                            addLog("用户已停止操作屏幕；当前暂停不允许自动恢复，保留原安全暂停状态")
                        }
                    } else {
                        addLog("收到 TYPE_TOUCH_INTERACTION_END，但当前没有匹配的触摸开始状态；未推断用户是否完成了实际操作")
                    }
                }
            }
            return
        }

        // Keep all non-touch event processing restricted to the configured target app.
        if (eventPackage != targetPackage || eventPackage == packageName) return

        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        ) {
            accountBrowseLastScrollEventAt = System.currentTimeMillis()
            accountBrowseScrollEventCount++
            if (!accountBrowseScrollEventSeen) {
                accountBrowseScrollEventSeen = true
                addLog("第 ${accountBrowseSwipeCount + 1} 轮滑动期间检测到目标应用列表滚动事件")
            }
        }

        if (!isAccountBrowseModeEnabled()) {
            resetAccountBrowseSession()
        } else if (accountBrowsePhase == AccountBrowsePhase.IDLE) {
            startAccountBrowseIfNeeded()
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

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && className.isNotBlank()) {
            handleMomoActivityTransition(className)
        }

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
            if (isTrackedInteractionEvent(eventType)) {
                add("操作来源判断=${classifyInteractionEventOrigin(eventType, now, className)}")
                add("当前 Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}")
                add("自动浏览阶段=${accountBrowsePhase.name}")
                add(accessibilitySourceDetails(event))
            }
        }.joinToString("；")
        addLog("$friendlyEvent$LOG_DETAIL_SEPARATOR$technicalDetails")

        handleManualInteractionFallback(
            eventType = eventType,
            className = className,
            content = content,
            description = description,
            event = event
        )

        // Scan once shortly after the latest event, not once for every event in a burst.
        mainHandler.removeCallbacks(scanRunnable)
        scanQueued = true
        mainHandler.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)
    }


    private fun startGestureCalibration(intent: Intent) {
        if (gestureCalibrationInProgress) {
            sendGestureCalibrationFailure("已有一组设备采样正在进行。")
            return
        }
        if (accountBrowseGestureInFlight || accountBrowseOverlayPassthroughActive) {
            sendGestureCalibrationFailure("当前自动浏览手势尚未结束，请停止当前操作后再采样。")
            return
        }

        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot == null) {
            sendGestureCalibrationFailure("无法读取 Freedom 校准页面；请确认校准窗口仍在前台。")
            return
        }
        val activePackage = try {
            activeRoot.packageName?.toString().orEmpty()
        } finally {
            runCatching { activeRoot.recycle() }
        }
        if (activePackage != packageName) {
            sendGestureCalibrationFailure(
                "当前前台不是 Freedom 校准页面。采样不会在陌陌或其他应用页面执行。"
            )
            return
        }

        val left = intent.getIntExtra(GestureTuning.EXTRA_LEFT, Int.MIN_VALUE)
        val top = intent.getIntExtra(GestureTuning.EXTRA_TOP, Int.MIN_VALUE)
        val width = intent.getIntExtra(GestureTuning.EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(GestureTuning.EXTRA_HEIGHT, 0)
        val display = resources.displayMetrics
        if (left == Int.MIN_VALUE || top == Int.MIN_VALUE ||
            width < dp(100) || height < dp(100) ||
            left < 0 || top < 0 ||
            left + width > display.widthPixels ||
            top + height > display.heightPixels
        ) {
            sendGestureCalibrationFailure("校准区域坐标无效或超出屏幕范围，请重新打开采样页面。")
            return
        }

        gestureCalibrationBounds = Rect(left, top, left + width, top + height)
        gestureCalibrationTrialIndex = 0
        gestureCalibrationInProgress = true
        addLog(
            "开始设备手势自动采样：仅在 Freedom 内部校准区域执行 " +
                "${GestureTuning.CALIBRATION_TRIAL_COUNT} 组测试手势"
        )
        mainHandler.removeCallbacks(gestureCalibrationRunnable)
        mainHandler.postDelayed(gestureCalibrationRunnable, 350L)
    }

    private fun dispatchNextGestureCalibrationTrial() {
        if (!gestureCalibrationInProgress) return
        val bounds = gestureCalibrationBounds
        if (bounds == null) {
            failGestureCalibration("校准区域信息已失效。")
            return
        }

        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        if (activeRoot == null) {
            failGestureCalibration("校准期间无法读取 Freedom 页面；测试已停止。")
            return
        }
        val activePackage = try {
            activeRoot.packageName?.toString().orEmpty()
        } finally {
            runCatching { activeRoot.recycle() }
        }
        if (activePackage != packageName) {
            failGestureCalibration("校准页面已离开 Freedom；为避免触碰其他应用，测试已立即停止。")
            return
        }

        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val centerX = bounds.exactCenterX()
        val startX = centerX - width * 0.12f
        val endX = centerX + width * 0.12f
        val startY = bounds.top + height * 0.76f
        val endY = bounds.top + height * 0.24f
        val control1X = centerX + width * 0.08f
        val control2X = centerX - width * 0.08f
        val control1Y = startY + (endY - startY) / 3f
        val control2Y = startY + (endY - startY) * 2f / 3f

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(control1X, control1Y, control2X, control2Y, endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    GestureTuning.CALIBRATION_GESTURE_DURATION_MS
                )
            )
            .build()

        val trialNumber = gestureCalibrationTrialIndex + 1
        val accepted = runCatching {
            dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (!gestureCalibrationInProgress) return
                        sendGestureCalibrationProgress(trialNumber)
                        gestureCalibrationTrialIndex++
                        if (gestureCalibrationTrialIndex >= GestureTuning.CALIBRATION_TRIAL_COUNT) {
                            mainHandler.postDelayed({
                                finishGestureCalibration()
                            }, 250L)
                        } else {
                            mainHandler.postDelayed(
                                gestureCalibrationRunnable,
                                GestureTuning.CALIBRATION_TRIAL_GAP_MS
                            )
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (gestureCalibrationInProgress) {
                            failGestureCalibration("Android 系统中断了第 ${trialNumber} 组内部测试手势。")
                        }
                    }
                },
                mainHandler
            )
        }.getOrDefault(false)

        if (!accepted && gestureCalibrationInProgress) {
            failGestureCalibration("Android 未接受第 ${trialNumber} 组内部测试手势。")
        }
    }

    private fun sendGestureCalibrationProgress(trialNumber: Int) {
        sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_TRIAL_COMPLETED)
                .setPackage(packageName)
                .putExtra(GestureTuning.EXTRA_TRIAL_INDEX, trialNumber)
        )
    }

    private fun finishGestureCalibration() {
        if (!gestureCalibrationInProgress) return
        gestureCalibrationInProgress = false
        gestureCalibrationBounds = null
        addLog("Freedom 内部手势采样完成；正在由主界面分析收到的 MotionEvent 时间戳")
        sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_FINISHED)
                .setPackage(packageName)
                .putExtra(GestureTuning.EXTRA_SUCCESS, true)
        )
    }

    private fun failGestureCalibration(reason: String) {
        if (!gestureCalibrationInProgress) {
            sendGestureCalibrationFailure(reason)
            return
        }
        gestureCalibrationInProgress = false
        gestureCalibrationBounds = null
        mainHandler.removeCallbacks(gestureCalibrationRunnable)
        addLog("设备手势自动采样中止：$reason")
        sendGestureCalibrationFailure(reason)
    }

    private fun cancelGestureCalibration(reason: String) {
        if (!gestureCalibrationInProgress) return
        gestureCalibrationInProgress = false
        gestureCalibrationBounds = null
        mainHandler.removeCallbacks(gestureCalibrationRunnable)
        addLog("设备手势自动采样已取消：$reason")
    }

    private fun sendGestureCalibrationFailure(reason: String) {
        sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_FAILED)
                .setPackage(packageName)
                .putExtra(GestureTuning.EXTRA_MESSAGE, reason)
        )
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
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: run {
            if (!accountBrowseRootUnavailableLogged) {
                addLog("恢复检查暂时无法读取当前前台页面；等待后续页面事件再次触发检查")
                accountBrowseRootUnavailableLogged = true
            }
            return
        }
        val isTargetForeground = runCatching {
            root.packageName?.toString().orEmpty() == MOMO_PACKAGE
        }.getOrDefault(false)
        runCatching { root.recycle() }
        if (!isTargetForeground) {
            if (!accountBrowseNotTargetForegroundLogged) {
                addLog("恢复检查发现当前前台不是陌陌；不会执行自动操作，等待进入陌陌后重新识别")
                accountBrowseNotTargetForegroundLogged = true
            }
            return
        }
        accountBrowseNotTargetForegroundLogged = false
        accountBrowseRootUnavailableLogged = false

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
        accountBrowseLastDynamicTabWaitReason = ""
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        addLog("已检测到陌陌进入前台；开始识别页面层级并定位首页顶部‘动态’标签（最多 ${INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS / 1_000} 秒），进入后再验证列表稳定性")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
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
        GestureTrailOverlay.clear()
        restoreMonitorOverlayTouchPassthrough()
        mainHandler.removeCallbacks(accountBrowseRunnable)
        mainHandler.removeCallbacks(manualTouchResumeRunnable)
        accountBrowseStepPending = false
        accountBrowsePhase = AccountBrowsePhase.IDLE
        accountBrowsePauseReason = ""
        accountBrowseLastDynamicTabWaitReason = ""
        accountBrowseDeadlineAt = 0L
        accountBrowseSourceFingerprint = ""
        accountBrowseSourceSnapshot = ""
        accountBrowseReturnedStableSamples = 0
        accountBrowseLastReturnedSnapshot = ""
        accountBrowseReturnMismatchLogged = false
        accountBrowseDetailFingerprint = ""
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        accountBrowseTouchActive = false
        accountBrowseManualResumeAllowed = false
        accountBrowseTouchStartedDuringGesture = false
        accountBrowseTouchEndedDuringGesture = false
        accountBrowseGestureInFlight = false
        accountBrowseScrollEventSeen = false
        accountBrowseScrollEventCount = 0
        accountBrowseLastScrollEventAt = 0L
        accountBrowseSwipeCount = 0
        accountBrowseFallbackScrollAttempted = false
        accountBrowseCountdownRemaining = 0
        accountBrowseCurrentActivityClass = ""
        accountBrowseImageBrowserReturnActivityClass = ""
        accountBrowseImageBrowserActive = false
        accountBrowseDetailDwellDueAt = 0L
        accountBrowseDetailDwellRemainingMs = 0L
        accountBrowseIgnoreManualEventsUntil = 0L
        accountBrowseProgrammaticActionLabel = ""
        accountBrowseSafeCardRecheckCount = 0
        accountBrowseLastGlobalTouchEventAt = 0L
        accountBrowseLastGlobalTouchEventName = ""
        accountBrowseLastGlobalTouchEventOrigin = ""
        accountBrowsePausedContext = null
        accountBrowseNavigationActionKey = ""
        accountBrowseNavigationBeforeActivity = ""
        accountBrowseNavigationBeforeFingerprint = ""
        accountBrowseNavigationActionDeadlineAt = 0L
        accountBrowseNavigationActionAttempts = 0
        accountBrowseRecoveryBackAttempts = 0
        accountBrowseRecoveryHomeClickAttempts = 0
        accountBrowseRecoveryDynamicClickAttempts = 0
        accountBrowseDynamicTabClickConfirmed = false
        accountBrowseNotTargetForegroundLogged = false
        accountBrowseRootUnavailableLogged = false
        hideFirstSwipeCountdown()
    }

    private fun pauseAccountBrowse(
        reason: String,
        allowManualResume: Boolean = false
    ) {
        if (allowManualResume && accountBrowsePhase != AccountBrowsePhase.PAUSED) {
            accountBrowsePausedContext = ManualResumeContext(
                phaseName = accountBrowsePhase.name,
                activityClass = accountBrowseCurrentActivityClass,
                sourceFingerprint = accountBrowseSourceFingerprint,
                detailFingerprint = accountBrowseDetailFingerprint,
                imageBrowserActive = accountBrowseImageBrowserActive,
                lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                capturedAt = System.currentTimeMillis()
            )
            addLog(
                "已保存暂停上下文：阶段=${accountBrowsePhase.name}；" +
                    "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；" +
                    "详情上下文=${if (accountBrowseDetailFingerprint.isNotBlank()) "已保存" else "无"}；" +
                    "图片浏览状态=$accountBrowseImageBrowserActive"
            )
        }
        GestureTrailOverlay.clear()
        restoreMonitorOverlayTouchPassthrough()
        mainHandler.removeCallbacks(accountBrowseRunnable)
        accountBrowseStepPending = false
        accountBrowseManualResumeAllowed = allowManualResume
        accountBrowseGestureInFlight = false
        accountBrowseCountdownRemaining = 0
        hideFirstSwipeCountdown()
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
            AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB -> waitForMomoDynamicTab()
            AccountBrowsePhase.VERIFY_NAVIGATION_ACTION -> verifyNavigationAction()
            AccountBrowsePhase.WAIT_FOR_FEED_STABLE -> waitForMomoFeedStable()
            AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN -> runFirstSwipeCountdown()
            AccountBrowsePhase.WAIT_FOR_LIST_SCROLL -> performFeedScroll()
            AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE -> verifyInitialSwipe()
            AccountBrowsePhase.WAIT_FOR_CARD_OPEN,
            AccountBrowsePhase.WAIT_FOR_SAFE_CARD -> openVisibleFeedCard()
            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD -> checkDetailPageLoaded()
            AccountBrowsePhase.DETAIL_DWELL -> returnFromDetailPage()
            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN -> waitForImageBrowserReturn()
            AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE -> waitForDetailRestabilize()
            AccountBrowsePhase.WAIT_FOR_LIST_RETURN -> checkFeedListReturned()
        }
    }

    /**
     * Analyze the current Momo page and actively navigate to Home -> Dynamic.
     * A click or Back is never treated as successful until the next page inspection verifies it.
     */
    private fun waitForMomoDynamicTab() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        var reason = "暂时无法读取当前页面"
        if (root != null) {
            try {
                val activePackage = runCatching { root.packageName?.toString().orEmpty() }
                    .getOrDefault("")
                if (activePackage != MOMO_PACKAGE) {
                    reason = "陌陌当前不在前台；保留导航上下文，不对其他应用执行操作"
                } else {
                    accountBrowseNotTargetForegroundLogged = false
                    accountBrowseRootUnavailableLogged = false

                    val tabInspection = inspectDynamicTabRow(root)
                    val tabEvidence = tabInspection.evidence
                    if (tabEvidence != null) {
                        if (tabEvidence.selectionKnown && !tabEvidence.dynamicSelected) {
                            val dynamicTarget = findClickableNodeForLabel(
                                root = root,
                                label = MOMO_DYNAMIC_TAB_LABEL,
                                expectedBounds = tabEvidence.dynamicBounds,
                                minCenterY = 0,
                                maxCenterY = (resources.displayMetrics.heightPixels * DYNAMIC_TAB_UPPER_BAND_RATIO).toInt()
                            )
                            if (dynamicTarget != null) {
                                submitNavigationClick(
                                    root = root,
                                    target = dynamicTarget,
                                    actionKey = NAV_ACTION_DYNAMIC_TAB,
                                    actionDescription = "首页顶部‘动态’标签"
                                )
                                return
                            }
                            reason = "已确认首页顶部动态导航行，但没有找到可安全点击的‘动态’控件"
                        } else {
                            val readiness = inspectMomoFeedReadiness(root)
                            val shouldVerifyUnknownSelectionByClick =
                                !tabEvidence.selectionKnown &&
                                    !accountBrowseDynamicTabClickConfirmed &&
                                    accountBrowseRecoveryDynamicClickAttempts < MAX_RECOVERY_TAB_CLICK_ATTEMPTS
                            if (shouldVerifyUnknownSelectionByClick) {
                                val dynamicTarget = findClickableNodeForLabel(
                                    root = root,
                                    label = MOMO_DYNAMIC_TAB_LABEL,
                                    expectedBounds = tabEvidence.dynamicBounds,
                                    minCenterY = 0,
                                    maxCenterY = (resources.displayMetrics.heightPixels * DYNAMIC_TAB_UPPER_BAND_RATIO).toInt()
                                )
                                if (dynamicTarget != null) {
                                    addLog("动态标签选中状态未被系统明确暴露；为确保进入目标页，主动点击已由同排导航结构确认的‘动态’并验证结果")
                                    submitNavigationClick(
                                        root = root,
                                        target = dynamicTarget,
                                        actionKey = NAV_ACTION_DYNAMIC_TAB,
                                        actionDescription = "首页顶部‘动态’标签"
                                    )
                                    return
                                }
                                reason = "已找到首页顶部动态导航行，但动态标签没有安全可点击的节点；不把当前列表直接认定为目标页"
                            }
                            if (readiness.fingerprint != null &&
                                (tabEvidence.selectionKnown || accountBrowseDynamicTabClickConfirmed)
                            ) {
                                accountBrowseImageBrowserActive = false
                                accountBrowseImageBrowserReturnActivityClass = ""
                                accountBrowseDetailDwellDueAt = 0L
                                accountBrowseDetailDwellRemainingMs = 0L
                                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
                                accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
                                accountBrowseLastDynamicTabWaitReason = ""
                                accountBrowseLastStableFingerprint = ""
                                accountBrowseStableSamples = 0
                                addLog(
                                    "已确认当前位于陌陌动态列表；现在独立等待列表稳定（最多 ${INITIAL_FEED_STABILITY_TIMEOUT_MS / 1_000} 秒）。" +
                                        readiness.reason
                                )
                                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                                return
                            }
                            if (!shouldVerifyUnknownSelectionByClick || accountBrowseDynamicTabClickConfirmed) {
                                reason = readiness.reason
                            }
                        }
                    } else {
                        val homeEvidence = inspectHomeBottomNav(root)
                        if (homeEvidence != null) {
                            val homeTarget = findClickableNodeForLabel(
                                root = root,
                                label = MOMO_HOME_TAB_LABEL,
                                expectedBounds = homeEvidence.homeBounds,
                                minCenterY = (resources.displayMetrics.heightPixels * HOME_NAV_LOWER_BAND_RATIO).toInt(),
                                maxCenterY = resources.displayMetrics.heightPixels
                            )
                            if (homeTarget != null) {
                                if (homeEvidence.selectionKnown && homeEvidence.homeSelected) {
                                    reason = "已确认底部‘首页’处于选中状态，但首页顶部动态导航行尚未出现；等待页面内容加载"
                                } else {
                                    submitNavigationClick(
                                        root = root,
                                        target = homeTarget,
                                        actionKey = NAV_ACTION_HOME_TAB,
                                        actionDescription = "陌陌底部‘首页’标签"
                                    )
                                    return
                                }
                            } else {
                                reason = "识别到陌陌底部‘首页’导航，但没有找到可安全点击的控件"
                            }
                        } else if (isKnownMomoChildPage()) {
                            if (accountBrowseRecoveryBackAttempts >= MAX_RECOVERY_BACK_STEPS) {
                                pauseAccountBrowse("已连续返回 $MAX_RECOVERY_BACK_STEPS 次，仍无法识别动态标签页；停止导航以避免误退出陌陌")
                                return
                            }
                            submitNavigationBack(root)
                            return
                        } else {
                            reason = when {
                                accountBrowseCurrentActivityClass == MOMO_MAIN_ACTIVITY ->
                                    "当前位于陌陌主 Activity，但无障碍节点暂未暴露可验证的首页/动态导航控件"
                                accountBrowseCurrentActivityClass.isBlank() ->
                                    "尚未取得可靠的陌陌 Activity 信息，也没有识别到动态导航行或底部首页导航"
                                else ->
                                    "当前页面层级无法确认；不执行猜测性返回或点击"
                            }
                        }
                    }
                }
            } finally {
                runCatching { root.recycle() }
            }
        }

        if (reason != accountBrowseLastDynamicTabWaitReason) {
            accountBrowseLastDynamicTabWaitReason = reason
            addLog("主动导航分析：$reason")
        }
        if (accountBrowsePausedContext != null) {
            // Manual recovery remains pending while the app/page is unavailable or unknown.
            // Keep analyzing at a low frequency; do not guess-click or give up after a timeout.
            accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
            scheduleAccountBrowseStep(1_000L)
            return
        }
        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("主动导航动态标签超时；当前页面未能被可靠识别，没有执行滑动或动态卡片点击")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun isKnownMomoChildPage(): Boolean {
        val activity = accountBrowseCurrentActivityClass
        return activity.isNotBlank() &&
            activity != MOMO_MAIN_ACTIVITY &&
            activity != packageName
    }

    private fun submitNavigationClick(
        root: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        actionKey: String,
        actionDescription: String
    ) {
        val targetBounds = Rect()
        runCatching { target.getBoundsInScreen(targetBounds) }
        val beforeFingerprint = pageFingerprint(root).signature
        val beforeActivity = accountBrowseCurrentActivityClass
        val attempt = when (actionKey) {
            NAV_ACTION_DYNAMIC_TAB -> ++accountBrowseRecoveryDynamicClickAttempts
            NAV_ACTION_HOME_TAB -> ++accountBrowseRecoveryHomeClickAttempts
            else -> 1
        }
        val maxAttempts = if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
            MAX_RECOVERY_TAB_CLICK_ATTEMPTS
        } else {
            MAX_RECOVERY_HOME_CLICK_ATTEMPTS
        }
        if (attempt > maxAttempts) {
            runCatching { target.recycle() }
            pauseAccountBrowse("导航控件‘$actionDescription’连续 $maxAttempts 次未能验证进入；已停止重复点击")
            return
        }

        accountBrowseProgrammaticActionLabel = "Freedom ACTION_CLICK $actionDescription"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + NAVIGATION_ACTION_EVENT_WINDOW_MS
        val clicked = try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { target.recycle() }
        }
        if (!clicked) {
            pauseAccountBrowse("系统未接受‘$actionDescription’的无障碍点击；没有继续尝试其他控件")
            return
        }

        accountBrowseNavigationActionKey = actionKey
        accountBrowseNavigationBeforeActivity = beforeActivity
        accountBrowseNavigationBeforeFingerprint = beforeFingerprint
        accountBrowseNavigationActionAttempts = attempt
        accountBrowseNavigationActionDeadlineAt = System.currentTimeMillis() + NAVIGATION_ACTION_VERIFY_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.VERIFY_NAVIGATION_ACTION
        addLog(
            "已向‘$actionDescription’提交无障碍点击（第 $attempt/$maxAttempts 次）；" +
                "目标控件范围=${if (targetBounds.isEmpty) "系统未提供" else targetBounds.toShortString()}；" +
                "等待重新扫描页面并验证导航结果，不会直接假定点击成功"
        )
        scheduleAccountBrowseStep(NAVIGATION_ACTION_VERIFY_POLL_MS)
    }

    private fun submitNavigationBack(root: AccessibilityNodeInfo) {
        val beforeFingerprint = pageFingerprint(root).signature
        val beforeActivity = accountBrowseCurrentActivityClass
        accountBrowseRecoveryBackAttempts++
        accountBrowseProgrammaticActionLabel = "Freedom GLOBAL_ACTION_BACK 页面层级返回"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + NAVIGATION_ACTION_EVENT_WINDOW_MS
        val accepted = runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
        if (!accepted) {
            pauseAccountBrowse("当前已识别为陌陌子页面，但系统未接受返回操作；停止自动导航")
            return
        }

        accountBrowseNavigationActionKey = NAV_ACTION_BACK
        accountBrowseNavigationBeforeActivity = beforeActivity
        accountBrowseNavigationBeforeFingerprint = beforeFingerprint
        accountBrowseNavigationActionAttempts = accountBrowseRecoveryBackAttempts
        accountBrowseNavigationActionDeadlineAt = System.currentTimeMillis() + NAVIGATION_ACTION_VERIFY_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.VERIFY_NAVIGATION_ACTION
        addLog(
            "当前页面识别为陌陌子页面（Activity=${beforeActivity.ifBlank { "未知" }}）；" +
                "已提交第 $accountBrowseRecoveryBackAttempts/$MAX_RECOVERY_BACK_STEPS 次返回；" +
                "等待验证返回后的页面层级"
        )
        scheduleAccountBrowseStep(NAVIGATION_ACTION_VERIFY_POLL_MS)
    }

    private fun verifyNavigationAction() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            if (System.currentTimeMillis() >= accountBrowseNavigationActionDeadlineAt) {
                accountBrowseNavigationActionKey = ""
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            }
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (activePackage != MOMO_PACKAGE) {
            runCatching { root.recycle() }
            if (System.currentTimeMillis() >= accountBrowseNavigationActionDeadlineAt) {
                accountBrowseNavigationActionKey = ""
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            }
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        val currentActivity = accountBrowseCurrentActivityClass
        val currentFingerprint = try {
            pageFingerprint(root).signature
        } finally {
            runCatching { root.recycle() }
        }
        val actionKey = accountBrowseNavigationActionKey
        val tabEvidence = if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
            val freshRoot = runCatching { rootInActiveWindow }.getOrNull()
            if (freshRoot == null) null else try {
                inspectDynamicTabRow(freshRoot).evidence
            } finally {
                runCatching { freshRoot.recycle() }
            }
        } else null
        val feedConfirmed = if (actionKey == NAV_ACTION_DYNAMIC_TAB || actionKey == NAV_ACTION_HOME_TAB) {
            val freshRoot = runCatching { rootInActiveWindow }.getOrNull()
            if (freshRoot == null) false else try {
                isMomoDynamicFeedPage(freshRoot)
            } finally {
                runCatching { freshRoot.recycle() }
            }
        } else false

        val structureChanged = currentActivity != accountBrowseNavigationBeforeActivity ||
            currentFingerprint != accountBrowseNavigationBeforeFingerprint
        val backPageEvidence = if (actionKey == NAV_ACTION_BACK) {
            val freshRoot = runCatching { rootInActiveWindow }.getOrNull()
            if (freshRoot == null) false else try {
                inspectDynamicTabRow(freshRoot).evidence != null ||
                    inspectHomeBottomNav(freshRoot) != null ||
                    (accountBrowseImageBrowserActive &&
                        currentActivity == accountBrowseImageBrowserReturnActivityClass)
            } finally {
                runCatching { freshRoot.recycle() }
            }
        } else false
        val confirmed = when (actionKey) {
            NAV_ACTION_DYNAMIC_TAB -> feedConfirmed ||
                (tabEvidence?.let { it.selectionKnown && it.dynamicSelected } == true) ||
                (tabEvidence != null && structureChanged)
            NAV_ACTION_HOME_TAB -> feedConfirmed || tabEvidence != null || structureChanged
            NAV_ACTION_BACK -> currentActivity != accountBrowseNavigationBeforeActivity ||
                (currentFingerprint != accountBrowseNavigationBeforeFingerprint && backPageEvidence)
            else -> false
        }

        if (confirmed) {
            if (actionKey == NAV_ACTION_DYNAMIC_TAB) {
                accountBrowseDynamicTabClickConfirmed = true
            }
            addLog("导航结果已验证：动作=$actionKey；Activity=${currentActivity.ifBlank { "未知" }}；页面结构已重新识别")
            accountBrowseNavigationActionKey = ""
            accountBrowseNavigationBeforeActivity = ""
            accountBrowseNavigationBeforeFingerprint = ""
            accountBrowseNavigationActionDeadlineAt = 0L
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            accountBrowseLastDynamicTabWaitReason = ""
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (System.currentTimeMillis() >= accountBrowseNavigationActionDeadlineAt) {
            val attempts = accountBrowseNavigationActionAttempts
            val maxAttempts = when (actionKey) {
                NAV_ACTION_BACK -> MAX_RECOVERY_BACK_STEPS
                NAV_ACTION_HOME_TAB -> MAX_RECOVERY_HOME_CLICK_ATTEMPTS
                NAV_ACTION_DYNAMIC_TAB -> MAX_RECOVERY_TAB_CLICK_ATTEMPTS
                else -> 1
            }
            addLog("导航结果未得到确认：动作=$actionKey；页面仍未出现可验证变化（$attempts/$maxAttempts 次）；重新分析当前页面后再决定下一步")
            accountBrowseNavigationActionKey = ""
            accountBrowseNavigationBeforeActivity = ""
            accountBrowseNavigationBeforeFingerprint = ""
            accountBrowseNavigationActionDeadlineAt = 0L
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            if (attempts >= maxAttempts && actionKey != NAV_ACTION_BACK) {
                pauseAccountBrowse("导航动作 $actionKey 连续 $maxAttempts 次未验证成功；已停止重复点击")
                return
            }
            if (actionKey == NAV_ACTION_BACK && accountBrowseRecoveryBackAttempts >= MAX_RECOVERY_BACK_STEPS) {
                pauseAccountBrowse("连续返回 $MAX_RECOVERY_BACK_STEPS 次后仍未能确认页面层级；已停止自动导航")
                return
            }
            scheduleAccountBrowseStep(NAVIGATION_ACTION_RETRY_DELAY_MS)
            return
        }

        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    private fun inspectHomeBottomNav(root: AccessibilityNodeInfo): HomeBottomNavEvidence? {
        val lowerBandTop = (resources.displayMetrics.heightPixels * HOME_NAV_LOWER_BAND_RATIO).toInt()
        val rowTolerance = maxOf(dp(DYNAMIC_TAB_ROW_TOLERANCE_DP), (resources.displayMetrics.heightPixels * 0.04f).toInt())
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to false)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val (node, selectedInPath) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in MOMO_HOME_NAV_LABELS -> text
                        description in MOMO_HOME_NAV_LABELS -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.centerY() >= lowerBandTop
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }
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
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        val homeLabels = labels.filter { it.label == MOMO_HOME_TAB_LABEL }
        for (home in homeLabels) {
            val peerLabels = labels.filter {
                it.label != MOMO_HOME_TAB_LABEL &&
                    kotlin.math.abs(it.bounds.centerY() - home.bounds.centerY()) <= rowTolerance
            }
            if (peerLabels.isEmpty()) continue
            val selectedLabels = (listOf(home) + peerLabels).filter { it.selected }.map { it.label }.distinct()
            val selectionKnown = selectedLabels.size == 1
            val homeSelected = selectionKnown && selectedLabels.single() == MOMO_HOME_TAB_LABEL
            return HomeBottomNavEvidence(
                homeBounds = Rect(home.bounds),
                homeSelected = homeSelected,
                selectionKnown = selectionKnown,
                diagnostic = if (selectionKnown) {
                    "底部导航选中项=${selectedLabels.single()}"
                } else {
                    "底部导航存在，但选中状态未能唯一确认"
                }
            )
        }
        return null
    }

    private fun findClickableNodeForLabel(
        root: AccessibilityNodeInfo,
        label: String,
        expectedBounds: Rect,
        minCenterY: Int,
        maxCenterY: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val nodeLabel = text.ifBlank { description }
                    val sameTarget = nodeLabel == label && node.isVisibleToUser &&
                        bounds.width() > 0 && bounds.height() > 0 &&
                        bounds.centerY() in minCenterY..maxCenterY &&
                        kotlin.math.abs(bounds.centerX() - expectedBounds.centerX()) <= dp(TAB_TARGET_MATCH_TOLERANCE_DP) &&
                        kotlin.math.abs(bounds.centerY() - expectedBounds.centerY()) <= dp(TAB_TARGET_MATCH_TOLERANCE_DP)
                    if (sameTarget) {
                        val clickable = findClickableAncestor(node, bounds)
                        if (clickable != null) return clickable
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return null
    }

    private fun findClickableAncestor(
        node: AccessibilityNodeInfo,
        labelBounds: Rect
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        val maxWidth = (resources.displayMetrics.widthPixels * 0.98f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * MAX_TAB_CLICK_TARGET_HEIGHT_RATIO).toInt()
        repeat(MAX_TAB_CLICK_ANCESTOR_DEPTH) {
            val candidate = current ?: return null
            val bounds = Rect()
            runCatching { candidate.getBoundsInScreen(bounds) }
            val usable = candidate.isVisibleToUser && candidate.isClickable &&
                bounds.width() > 0 && bounds.width() <= maxWidth &&
                bounds.height() > 0 && bounds.height() <= maxHeight &&
                bounds.contains(labelBounds)
            if (usable) return candidate
            val parent = runCatching { candidate.parent }.getOrNull()
            runCatching { candidate.recycle() }
            current = parent
        }
        runCatching { current?.recycle() }
        return null
    }

    private fun waitForMomoFeedStable() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            scheduleOrTimeoutFeedStability()
            return
        }

        val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (activePackage != MOMO_PACKAGE) {
            runCatching { root.recycle() }
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
            scheduleOrTimeoutFeedStability()
            return
        }

        val inspection = try {
            inspectMomoFeedReadiness(root)
        } finally {
            runCatching { root.recycle() }
        }
        val fingerprint = inspection.fingerprint

        if (fingerprint == null) {
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
            addLog("动态列表尚不具备操作条件：${inspection.reason}")
        } else if (fingerprint.signature == accountBrowseLastStableFingerprint) {
            accountBrowseStableSamples++
            addLog("动态列表采样：可见项目=${fingerprint.nodeCount}，稳定计数=${accountBrowseStableSamples}/$REQUIRED_STABLE_FEED_SAMPLES；${inspection.reason}")
        } else {
            accountBrowseLastStableFingerprint = fingerprint.signature
            accountBrowseStableSamples = 1
            addLog("动态列表布局基准已建立：可见项目=${fingerprint.nodeCount}，稳定计数=1/$REQUIRED_STABLE_FEED_SAMPLES；${inspection.reason}")
        }

        if (accountBrowseStableSamples >= REQUIRED_STABLE_FEED_SAMPLES) {
            accountBrowseSwipeCount = 0
            if (accountBrowsePausedContext != null) {
                addLog("暂停上下文已完成校验：当前动态列表连续稳定；恢复后先执行新的列表滑动，再选择动态，避免重新点击暂停前已打开的卡片")
                accountBrowsePausedContext = null
            }
            accountBrowsePhase = AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN
            accountBrowseCountdownRemaining = FIRST_SWIPE_COUNTDOWN_SECONDS
            addLog(
                "已连续确认陌陌动态列表稳定 ${REQUIRED_STABLE_FEED_SAMPLES} 次；" +
                    "即将开始持续滑动循环，现在开始首次滑动倒计时"
            )
            if (!showFirstSwipeCountdown(accountBrowseCountdownRemaining)) {
                pauseAccountBrowse("无法显示首次滑动倒计时提示；为避免无提示操作，已暂停")
                return
            }
            addLog("首次滑动倒计时：${accountBrowseCountdownRemaining} 秒")
            scheduleAccountBrowseStep(FIRST_SWIPE_COUNTDOWN_STEP_MS)
            return
        }

        scheduleOrTimeoutFeedStability()
    }

    private fun scheduleOrTimeoutFeedStability() {
        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("动态列表加载或稳定确认超时；没有执行首次滑动")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    /** Keep the user informed for three seconds, and re-check the feed before dispatch. */
    private fun runFirstSwipeCountdown() {
        val root = obtainTargetRootOrPause() ?: return
        val isFeed = try {
            isMomoDynamicFeedPage(root)
        } finally {
            runCatching { root.recycle() }
        }
        if (!isFeed) {
            pauseAccountBrowse("倒计时期间动态列表状态发生变化；已取消首次滑动")
            return
        }

        if (accountBrowseCountdownRemaining > 1) {
            accountBrowseCountdownRemaining--
            if (!showFirstSwipeCountdown(accountBrowseCountdownRemaining)) {
                pauseAccountBrowse("首次滑动倒计时提示中断；已取消首次滑动")
                return
            }
            addLog("首次滑动倒计时：${accountBrowseCountdownRemaining} 秒")
            scheduleAccountBrowseStep(FIRST_SWIPE_COUNTDOWN_STEP_MS)
            return
        }

        accountBrowseCountdownRemaining = 0
        hideFirstSwipeCountdown()
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
        performFeedScroll()
    }

    /** Use a non-touchable accessibility overlay so the countdown remains visible over Momo. */
    private fun showFirstSwipeCountdown(seconds: Int): Boolean {
        val existing = firstSwipeCountdownView
        if (existing != null) {
            existing.text = "首次滑动倒计时\n$seconds\n请观察动态列表是否移动"
            return true
        }

        val wm = runCatching {
            getSystemService(WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return false
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "首次滑动倒计时\n$seconds\n请观察动态列表是否移动"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                setColor(0xE6293548.toInt())
                cornerRadius = 16f * density
                setStroke(dp(1), 0xFF9AA8BC.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }

        return runCatching {
            wm.addView(view, params)
            firstSwipeCountdownWindow = wm
            firstSwipeCountdownView = view
            true
        }.getOrDefault(false)
    }

    private fun hideFirstSwipeCountdown() {
        val view = firstSwipeCountdownView
        if (view != null) {
            runCatching { firstSwipeCountdownWindow?.removeView(view) }
        }
        firstSwipeCountdownView = null
        firstSwipeCountdownWindow = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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

    /** Temporarily remove the live monitor window from the touch path for the swipe. */
    private fun enableMonitorOverlayTouchPassthrough(): Boolean {
        if (!MonitorOverlay.isAttached()) {
            accountBrowseOverlayPassthroughActive = false
            addLog("实时监控悬浮窗当前未附加（配置中‘悬浮日志’已关闭）；本次滑动不经过悬浮窗")
            return true
        }
        val before = MonitorOverlay.touchStateDescription()
        val enabled = MonitorOverlay.setTouchPassthrough(true)
        if (!enabled) {
            addLog("无法暂时关闭实时监控悬浮窗的触摸拦截；未发送首次滑动。$before")
            return false
        }
        accountBrowseOverlayPassthroughActive = true
        mainHandler.removeCallbacks(monitorOverlayPassthroughWatchdog)
        mainHandler.postDelayed(
            monitorOverlayPassthroughWatchdog,
            MONITOR_OVERLAY_PASSTHROUGH_WATCHDOG_MS
        )
        addLog(
            "首次滑动前已临时隐藏实时监控悬浮窗并启用触摸穿透。" +
                "$before；切换后=${MonitorOverlay.touchStateDescription()}"
        )
        return true
    }

    private fun restoreMonitorOverlayTouchPassthrough(): Boolean {
        if (!accountBrowseOverlayPassthroughActive) return true
        val restored = MonitorOverlay.setTouchPassthrough(false)
        if (restored) {
            accountBrowseOverlayPassthroughActive = false
            mainHandler.removeCallbacks(monitorOverlayPassthroughWatchdog)
            addLog("首次滑动检查结束：实时监控悬浮窗已恢复显示和触摸操作")
            return true
        }
        addLog("警告：实时监控悬浮窗触摸状态恢复失败；悬浮窗可能仍处于隐藏/穿透状态")
        return false
    }

    /** Resolve the same visible feed container used by the readiness gate and return
     * its screen-space bounds, clipped below the Dynamic tab row and to the display. */
    private fun resolveFeedGestureTarget(root: AccessibilityNodeInfo): FeedGestureTarget? {
        val tabInspection = inspectDynamicTabRow(root)
        val tabEvidence = tabInspection.evidence ?: run {
            addLog("无法取得首次滑动区域：${tabInspection.reason}")
            return null
        }
        val listNode = findVisibleFeedContainer(root, tabEvidence.contentTop) ?: run {
            addLog("无法取得首次滑动区域：动态标签下方没有可见列表容器")
            return null
        }
        return try {
            val rawBounds = Rect()
            listNode.getBoundsInScreen(rawBounds)
            val metrics = resources.displayMetrics
            val clipped = Rect(
                maxOf(rawBounds.left, 0),
                maxOf(rawBounds.top, tabEvidence.contentTop, 0),
                minOf(rawBounds.right, metrics.widthPixels),
                minOf(rawBounds.bottom, metrics.heightPixels)
            )
            val className = listNode.className?.toString()?.substringAfterLast('.') ?: "Unknown"
            if (clipped.width() < MIN_GESTURE_WIDTH_PX ||
                clipped.height() < MIN_GESTURE_HEIGHT_PX
            ) {
                addLog(
                    "目标列表有效滑动区域过小：class=$className，" +
                        "原始边界=$rawBounds，有效边界=$clipped"
                )
                null
            } else {
                FeedGestureTarget(clipped, className)
            }
        } catch (error: Exception) {
            addLog("读取动态列表屏幕边界失败：${error.javaClass.simpleName}")
            null
        } finally {
            runCatching { listNode.recycle() }
        }
    }


    /**
     * First-stage verification: dispatch one swipe inside the confirmed visible feed container.
     * Card recognition and card interaction remain deferred until after this initial scroll check.
     */
    private fun performFeedScroll() {
        val root = obtainTargetRootOrPause() ?: return
        val target = try {
            accountBrowseSourceSnapshot = captureVisibleFeedNodeSnapshot(root)
            resolveFeedGestureTarget(root)
        } finally {
            runCatching { root.recycle() }
        } ?: run {
            pauseAccountBrowse("无法定位首页动态列表的有效滑动区域；本轮未发送滑动")
            return
        }
        addLog("第 ${accountBrowseSwipeCount + 1} 轮滑动前可见节点快照：${accountBrowseSourceSnapshot.ifBlank { "未提取到可见文字节点" }}")

        val bounds = target.bounds
        val profile = loadScrollGestureProfile()
        val gesturePlan = createSmoothScrollPath(bounds, profile) ?: run {
            pauseAccountBrowse("动态列表有效区域不足以生成安全滑动路径；本轮未发送滑动")
            return
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(gesturePlan.path, 0L, profile.durationMs))
            .build()

        if (!enableMonitorOverlayTouchPassthrough()) {
            pauseAccountBrowse("实时监控悬浮窗无法切换为触摸穿透；本轮滑动未执行")
            return
        }

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE
        accountBrowseScrollEventSeen = false
        accountBrowseScrollEventCount = 0
        accountBrowseProgrammaticActionLabel = "Freedom dispatchGesture 自动坐标滑动"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + profile.durationMs + 2_000L
        accountBrowseLastScrollEventAt = 0L
        accountBrowseFallbackScrollAttempted = false
        accountBrowseGestureInFlight = false
        accountBrowseTouchStartedDuringGesture = false
        accountBrowseTouchEndedDuringGesture = false
        addLog(
            "第 ${accountBrowseSwipeCount + 1} 轮列表区域坐标滑动准备：容器=${target.className}，边界=$bounds，" +
                "起点 (${gesturePlan.startX},${gesturePlan.startY})，终点 (${gesturePlan.endX},${gesturePlan.endY})，" +
                "持续 ${profile.durationMs} 毫秒"
        )

        // Give WindowManager a short frame interval to apply any overlay visibility/flag change.
        mainHandler.postDelayed({
            if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ||
                accountBrowseTouchActive || !MonitorStore.isRunning(this)
            ) {
                accountBrowseGestureInFlight = false
                restoreMonitorOverlayTouchPassthrough()
                return@postDelayed
            }
            accountBrowseGestureInFlight = true
            val accepted = runCatching {
                dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        GestureTrailOverlay.finishSwipe()
                        accountBrowseGestureInFlight = false
                        accountBrowseTouchStartedDuringGesture = false
                        accountBrowseTouchEndedDuringGesture = false
                        if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ||
                            !MonitorStore.isRunning(this@FreedomAccessibilityService)
                        ) return
                        addLog("Android 已完成第 ${accountBrowseSwipeCount + 1} 轮屏幕滑动手势；正在检查真实列表滚动")
                        scheduleAccountBrowseStep(SCROLL_SETTLE_MS)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        GestureTrailOverlay.cancelSwipe()
                        val touchStarted = accountBrowseTouchStartedDuringGesture
                        val touchEnded = accountBrowseTouchEndedDuringGesture
                        accountBrowseGestureInFlight = false
                        accountBrowseTouchStartedDuringGesture = false
                        accountBrowseTouchEndedDuringGesture = false
                        if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) {
                            val interruptedByTouch = touchStarted || touchEnded
                            pauseAccountBrowse(
                                "第 ${accountBrowseSwipeCount + 1} 轮屏幕滑动被系统或用户操作中断",
                                allowManualResume = interruptedByTouch
                            )
                            if (interruptedByTouch) {
                                if (touchEnded) {
                                    accountBrowseTouchActive = false
                                    addLog("检测到手动触摸中断了自动滑动，且触摸已经结束；开始 ${MANUAL_TOUCH_RESUME_MS / 1000.0} 秒静默等待")
                                    mainHandler.removeCallbacks(manualTouchResumeRunnable)
                                    mainHandler.postDelayed(manualTouchResumeRunnable, MANUAL_TOUCH_RESUME_MS)
                                } else {
                                    accountBrowseTouchActive = true
                                    addLog("检测到手动触摸中断了自动滑动；等待用户松开屏幕后再开始静默等待")
                                }
                            }
                        }
                    }
                }, mainHandler)
            }.getOrDefault(false)

            addLog("dispatchGesture 返回：accepted=$accepted")
            if (!accepted) {
                GestureTrailOverlay.clear()
                accountBrowseGestureInFlight = false
                pauseAccountBrowse("Android 未接受第 ${accountBrowseSwipeCount + 1} 轮屏幕滑动手势；已停止本轮浏览")
                return@postDelayed
            }
            // Display the exact Path object submitted to GestureDescription; do not synthesize a separate curve.
            GestureTrailOverlay.showSwipe(this, gesturePlan.path, profile.durationMs)
            addLog("第 ${accountBrowseSwipeCount + 1} 轮列表区域滑动手势已提交；轨迹可视化与实际手势共用同一条 Path，等待系统完成回调")
        }, SWIPE_WINDOW_SYNC_DELAY_MS)
    }

    /** Verify a real list-scroll event, wait for the list to become idle, then schedule the next round. */
    private fun verifyInitialSwipe() {
        if (accountBrowsePhase != AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE) return
        if (accountBrowseGestureInFlight) {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (accountBrowseScrollEventSeen) {
            val quietForMs = System.currentTimeMillis() - accountBrowseLastScrollEventAt
            if (quietForMs < SCROLL_SETTLE_MS) {
                scheduleAccountBrowseStep((SCROLL_SETTLE_MS - quietForMs).coerceAtLeast(100L))
                return
            }
        }

        if (!restoreMonitorOverlayTouchPassthrough()) {
            pauseAccountBrowse("第 ${accountBrowseSwipeCount + 1} 轮滑动后无法恢复实时监控悬浮窗；已停止后续操作")
            return
        }

        val root = obtainTargetRootOrPause() ?: return
        val afterSnapshot = try {
            if (isMomoDynamicFeedPage(root)) {
                captureVisibleFeedNodeSnapshot(root)
            } else {
                ""
            }
        } finally {
            runCatching { root.recycle() }
        }
        addLog("第 ${accountBrowseSwipeCount + 1} 轮滑动后可见节点快照：${afterSnapshot.ifBlank { "动态列表快照不可用" }}")

        if (!accountBrowseScrollEventSeen) {
            pauseAccountBrowse("第 ${accountBrowseSwipeCount + 1} 轮手势已提交，但没有收到目标列表 TYPE_VIEW_SCROLLED；未计入成功轮数")
            return
        }
        if (afterSnapshot.isBlank()) {
            pauseAccountBrowse("第 ${accountBrowseSwipeCount + 1} 轮收到滚动事件，但滑动后无法重新确认动态列表；已暂停")
            return
        }

        val snapshotsDiffer = accountBrowseSourceSnapshot.isNotBlank() &&
            accountBrowseSourceSnapshot != afterSnapshot
        accountBrowseSwipeCount++
        addLog(
            "第 $accountBrowseSwipeCount 轮真实滑动已确认；" +
                "TYPE_VIEW_SCROLLED 事件数=$accountBrowseScrollEventCount；" +
                "前后节点快照${if (snapshotsDiffer) "发生变化" else "未显示明显差异"}"
        )

        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_CARD_OPEN
        addLog(
            "动态列表已停止滚动；累计已确认滑动=$accountBrowseSwipeCount 轮，" +
                "等待列表稳定后识别并打开一条普通动态"
        )
        scheduleAccountBrowseStep(CARD_OPEN_SETTLE_MS)
    }

    /** Capture a small, readable sample of visible text nodes and their screen positions. */
    private fun captureVisibleFeedNodeSnapshot(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val lines = ArrayList<String>(SNAPSHOT_MAX_TEXT_NODES)
        var visited = 0
        val screenHeight = resources.displayMetrics.heightPixels
        try {
            while (queue.isNotEmpty() && visited < SNAPSHOT_MAX_NODES &&
                lines.size < SNAPSHOT_MAX_TEXT_NODES
            ) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        val text = runCatching { node.text?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val content = sequenceOf(text, description)
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                        if (content.isNotBlank() && bounds.top >= (screenHeight * SNAPSHOT_TOP_RATIO).toInt() &&
                            bounds.top < (screenHeight * SNAPSHOT_BOTTOM_RATIO).toInt()
                        ) {
                            val nodeClass = node.className?.toString().orEmpty().substringAfterLast('.')
                            lines.add(
                                "$nodeClass:${content.take(SNAPSHOT_TEXT_LENGTH)}@" +
                                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                            )
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= SNAPSHOT_MAX_NODES ||
                            lines.size >= SNAPSHOT_MAX_TEXT_NODES
                        ) break
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
        return lines.joinToString(" | ").take(SNAPSHOT_MAX_LOG_LENGTH)
    }

    private fun loadScrollGestureProfile(): ScrollGestureProfile {
        val prefs = getSharedPreferences(ACCOUNT_BROWSE_PREFS, Context.MODE_PRIVATE)
        return ScrollGestureProfile(
            durationMs = prefs.getLong(KEY_BROWSE_GESTURE_DURATION_MS, DEFAULT_GESTURE_DURATION_MS)
                .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS),
            curveOffsetRatio = (prefs.getFloat(KEY_BROWSE_GESTURE_CURVE_OFFSET, DEFAULT_CURVE_OFFSET_RATIO))
                .coerceIn(0f, MAX_CURVE_OFFSET_RATIO),
            microPerturbationFrequencyHz = GestureTuning.currentFrequencyHz(this)
        )
    }

    /** Create fresh in-bounds endpoints and a cosine-timed cubic Bezier scroll path. */
    private fun createSmoothScrollPath(
        bounds: Rect,
        profile: ScrollGestureProfile
    ): ScrollGesturePlan? {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val horizontalInset = maxOf(
            (8f * density).toInt(),
            (bounds.width() * 0.04f).toInt()
        ).coerceAtMost(bounds.width() / 4)
        val verticalInset = (8f * density).toInt().coerceAtMost(bounds.height() / 8)

        val safeLeft = bounds.left + horizontalInset
        val safeRight = bounds.right - horizontalInset
        val safeTop = bounds.top + verticalInset
        val safeBottom = bounds.bottom - verticalInset
        val safeWidth = (safeRight - safeLeft).toFloat()
        val safeHeight = (safeBottom - safeTop).toFloat()
        if (safeWidth < 80f || safeHeight < 120f) return null

        fun randomBetween(min: Float, max: Float): Float =
            min + Random.nextFloat() * (max - min)

        // Select a new start/end point from the currently visible list area on every swipe.
        val startX = (safeLeft + safeWidth * randomBetween(0.18f, 0.82f)).toInt()
        val endX = (
            startX + safeWidth * randomBetween(-0.025f, 0.025f)
        ).toInt().coerceIn(safeLeft, safeRight)
        val startY = (safeTop + safeHeight * randomBetween(0.68f, 0.82f)).toInt()
        val endY = (safeTop + safeHeight * randomBetween(0.22f, 0.38f)).toInt()

        val startXf = startX.toFloat()
        val startYf = startY.toFloat()
        val endXf = endX.toFloat()
        val endYf = endY.toFloat()
        val curveLimit = (bounds.width() * profile.curveOffsetRatio)
            .coerceAtMost(bounds.width() * MAX_CURVE_OFFSET_RATIO)
        val control1X = (startXf + randomBetween(-curveLimit, curveLimit))
            .coerceIn(safeLeft.toFloat(), safeRight.toFloat())
        val control2X = (endXf + randomBetween(-curveLimit, curveLimit))
            .coerceIn(safeLeft.toFloat(), safeRight.toFloat())
        val verticalDistance = endYf - startYf
        val control1Y = startYf + verticalDistance / 3f
        val control2Y = startYf + verticalDistance * 2f / 3f

        val path = Path()
        val useMicroPerturbation = ENABLE_SCROLL_MICRO_PERTURBATION
        val steps = if (useMicroPerturbation) {
            MICRO_PERTURBATION_PATH_STEPS
        } else {
            GESTURE_PATH_STEPS
        }

        for (step in 0..steps) {
            val t = step.toFloat() / steps.toFloat()
            // 2.1: cosine ease-in-out controls progress along the Bezier curve.
            val progress = (0.5 - 0.5 * cos(PI * t.toDouble())).toFloat()
            val inverse = 1f - progress
            val inverseSquared = inverse * inverse
            val progressSquared = progress * progress

            val x = inverseSquared * inverse * startXf +
                3f * inverseSquared * progress * control1X +
                3f * inverse * progressSquared * control2X +
                progressSquared * progress * endXf
            val y = inverseSquared * inverse * startYf +
                3f * inverseSquared * progress * control1Y +
                3f * inverse * progressSquared * control2Y +
                progressSquared * progress * endYf

            var pathX = x
            var pathY = y
            if (useMicroPerturbation && step > 0 && step < steps) {
                // 5.1: calibrated device frequency, 1.2 px offset along the local curve normal.
                val tangentX = 3f * inverseSquared * (control1X - startXf) +
                    6f * inverse * progress * (control2X - control1X) +
                    3f * progressSquared * (endXf - control2X)
                val tangentY = 3f * inverseSquared * (control1Y - startYf) +
                    6f * inverse * progress * (control2Y - control1Y) +
                    3f * progressSquared * (endYf - control2Y)
                val tangentLength = hypot(tangentX.toDouble(), tangentY.toDouble())
                    .toFloat().coerceAtLeast(1f)
                val elapsedSeconds = t * profile.durationMs / 1_000f
                val wave = sin(
                    2.0 * PI * profile.microPerturbationFrequencyHz * elapsedSeconds.toDouble()
                ).toFloat() * MICRO_PERTURBATION_AMPLITUDE_PX
                pathX += (-tangentY / tangentLength) * wave
                pathY += (tangentX / tangentLength) * wave
            }

            if (step == 0) path.moveTo(pathX, pathY) else path.lineTo(pathX, pathY)
        }
        return ScrollGesturePlan(path, startX, startY, endX, endY)
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
            accountBrowseProgrammaticActionLabel = "Freedom ACTION_SCROLL_FORWARD 备用滚动"
            accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + 1_800L
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
        var scrollNode: AccessibilityNodeInfo? = null
        var cardNode: AccessibilityNodeInfo? = null
        try {
            if (!isMomoDynamicFeedPage(root)) {
                pauseAccountBrowse("滑动后页面不再是可识别的陌陌动态列表；已暂停")
                return
            }

            val listNode = findScrollableFeedContainer(root)
            if (listNode == null) {
                pauseAccountBrowse("滑动后未找到动态列表；已暂停")
                return
            }
            scrollNode = listNode

            // Save the exact list state immediately before opening the card. The same
            // list-only snapshot is compared after Back to avoid scrolling from an
            // unverified or shifted position.
            accountBrowseSourceFingerprint = pageFingerprint(root).signature
            accountBrowseSourceSnapshot = captureVisibleFeedNodeSnapshot(listNode)
            cardNode = findClickableFeedCard(listNode)
        } finally {
            runCatching { scrollNode?.recycle() }
            runCatching { root.recycle() }
        }

        if (cardNode == null) {
            if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SAFE_CARD) {
                accountBrowseSafeCardRecheckCount++
                if (accountBrowseSafeCardRecheckCount >= MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE) {
                    accountBrowseSafeCardRecheckCount = 0
                    accountBrowseScrollEventSeen = false
                    accountBrowseScrollEventCount = 0
                    accountBrowseLastScrollEventAt = 0L
                    accountBrowseFallbackScrollAttempted = false
                    accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                    val nextSwipeDelay = randomScrollIntervalMs()
                    addLog(
                        "连续 ${MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE} 次复查仍未发现可安全打开的普通动态；" +
                            "广告、直播和未知卡片继续全部跳过，${nextSwipeDelay / 1_000.0} 秒后继续滑动列表搜索"
                    )
                    scheduleAccountBrowseStep(nextSwipeDelay)
                } else {
                    addLog(
                        "第 $accountBrowseSafeCardRecheckCount 次定时复查：当前仍没有符合条件的普通动态；" +
                            "保持等待 ${SAFE_CARD_RECHECK_INTERVAL_MS / 1_000.0} 秒，不点击广告、直播或未知卡片"
                    )
                    scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
                }
            } else {
                accountBrowseSafeCardRecheckCount = 0
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_SAFE_CARD
                addLog(
                    "本轮没有找到可安全打开的普通动态；已跳过广告、直播和未知卡片，" +
                        "进入周期性等待，每 ${SAFE_CARD_RECHECK_INTERVAL_MS / 1_000.0} 秒重新检查；" +
                        "连续复查仍无合适卡片时会继续滑动列表搜索，不会永久停在 PAUSED"
                )
                scheduleAccountBrowseStep(SAFE_CARD_RECHECK_INTERVAL_MS)
            }
            return
        }
        accountBrowseSafeCardRecheckCount = 0

        // ACTION_CLICK activates an accessibility node rather than injecting a physical tap.
        // Capture its real screen bounds so the overlay can mark the target control accurately.
        val clickTargetBounds = Rect()
        runCatching { cardNode?.getBoundsInScreen(clickTargetBounds) }
        accountBrowseProgrammaticActionLabel = "Freedom ACTION_CLICK 动态卡片"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + PROGRAMMATIC_CLICK_EVENT_WINDOW_MS
        val clicked = try {
            cardNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { cardNode?.recycle() }
        }

        if (!clicked) {
            pauseAccountBrowse("动态卡片未接受无障碍点击；已停止本轮操作")
            return
        }

        if (!clickTargetBounds.isEmpty) {
            GestureTrailOverlay.showClick(this, clickTargetBounds)
            addLog("点击可视化：已标出被 ACTION_CLICK 激活的动态卡片控件范围及其中心位置；该操作是无障碍节点点击，不是模拟手指落点")
        } else {
            addLog("点击可视化未显示：系统没有返回动态卡片的有效屏幕边界")
        }
        accountBrowseReturnedStableSamples = 0
        accountBrowseLastReturnedSnapshot = ""
        accountBrowseReturnMismatchLogged = false
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD
        accountBrowseDeadlineAt = System.currentTimeMillis() + DETAIL_LOAD_TIMEOUT_MS
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        addLog("已点击一条符合条件的普通动态；正在确认详情页面是否成功载入")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Select a direct visible card from the feed list; detailed rules live in browse/. */
    private fun findClickableFeedCard(
        scrollNode: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val minWidth = (resources.displayMetrics.widthPixels * MIN_FEED_CARD_WIDTH_RATIO).toInt()
        val minHeight = (resources.displayMetrics.heightPixels * MIN_FEED_CARD_HEIGHT_RATIO).toInt()
        val result = MomoDynamicCardSelector.select(
            scrollNode = scrollNode,
            minWidthPx = minWidth,
            minHeightPx = minHeight
        )
        if (result.skippedLiveStreamCount > 0) {
            addLog("卡片筛选：已跳过 ${result.skippedLiveStreamCount} 条疑似直播间动态")
        }
        if (result.skippedAdvertisementCount > 0) {
            addLog("卡片筛选：已跳过 ${result.skippedAdvertisementCount} 条广告或推广卡片")
        }
        if (result.skippedUnknownCount > 0) {
            addLog("卡片筛选：已跳过 ${result.skippedUnknownCount} 条无法确认类型的卡片")
        }
        if (result.node == null) {
            addLog("卡片筛选未通过：${result.reason}；检查候选=${result.inspectedCardCount}，直播=${result.skippedLiveStreamCount}，广告=${result.skippedAdvertisementCount}，未知=${result.skippedUnknownCount}")
        } else {
            addLog("卡片筛选通过：已确认候选具有可识别的普通社交卡片结构")
        }
        return result.node
    }

    private fun checkDetailPageLoaded() {
        val root = obtainTargetRootOrPause() ?: return
        val pageState = try {
            isMomoDynamicFeedPage(root) to pageFingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }
        val isFeedPage = pageState.first
        val fingerprint = pageState.second

        if (!isFeedPage &&
            fingerprint.signature != accountBrowseSourceFingerprint &&
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
                accountBrowseDetailDwellRemainingMs = dwell
                accountBrowseDetailDwellDueAt = System.currentTimeMillis() + dwell
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
        val pageState = try {
            isMomoDynamicFeedPage(root) to pageFingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }
        val isFeedPage = pageState.first
        val fingerprint = pageState.second
        if (isFeedPage ||
            fingerprint.signature != accountBrowseDetailFingerprint ||
            fingerprint.signature == accountBrowseSourceFingerprint
        ) {
            pauseAccountBrowse("阅读期间页面结构发生变化；为避免返回到错误页面，已暂停")
            return
        }

        accountBrowseProgrammaticActionLabel = "Freedom GLOBAL_ACTION_BACK 返回详情"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + 1_500L
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            pauseAccountBrowse("系统未能执行返回操作；请手动返回动态列表后重新启动")
            return
        }
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
        accountBrowseReturnedStableSamples = 0
        accountBrowseLastReturnedSnapshot = ""
        accountBrowseReturnMismatchLogged = false
        accountBrowseDeadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
        addLog("阅读停留结束；正在确认已返回动态列表")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /**
     * Track the known Momo photo-browser Activity as a child page of the current dynamic detail.
     * Only the exact Activity that was active before the photo browser is accepted as its return.
     */
    private fun handleMomoActivityTransition(activityClass: String) {
        if (!activityClass.endsWith("Activity")) return

        val previousActivity = accountBrowseCurrentActivityClass
        accountBrowseCurrentActivityClass = activityClass

        if (activityClass == MOMO_FEED_IMAGE_BROWSER_ACTIVITY) {
            if (accountBrowsePhase == AccountBrowsePhase.PAUSED && accountBrowseManualResumeAllowed) {
                accountBrowseImageBrowserReturnActivityClass = previousActivity
                    .takeIf { it.isNotBlank() && it != MOMO_FEED_IMAGE_BROWSER_ACTIVITY }
                    ?: MOMO_DYNAMIC_DETAIL_ACTIVITY
                accountBrowseImageBrowserActive = true
                accountBrowseDetailDwellRemainingMs =
                    (accountBrowseDetailDwellDueAt - System.currentTimeMillis()).coerceAtLeast(0L)
                addLog("已识别用户暂停期间进入陌陌动态图片浏览页面：保留 PAUSED 状态；静默结束后由恢复导航逐级返回动态标签")
            } else if (accountBrowsePhase == AccountBrowsePhase.DETAIL_DWELL) {
                accountBrowseImageBrowserReturnActivityClass = previousActivity
                    .takeIf { it.isNotBlank() && it != MOMO_FEED_IMAGE_BROWSER_ACTIVITY }
                    ?: MOMO_DYNAMIC_DETAIL_ACTIVITY
                accountBrowseImageBrowserActive = true
                accountBrowseDetailDwellRemainingMs =
                    (accountBrowseDetailDwellDueAt - System.currentTimeMillis()).coerceAtLeast(0L)
                mainHandler.removeCallbacks(manualTouchResumeRunnable)
                mainHandler.removeCallbacks(accountBrowseRunnable)
                accountBrowseManualResumeAllowed = false
                accountBrowseStepPending = false
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN
                accountBrowseDeadlineAt = System.currentTimeMillis() + IMAGE_BROWSER_RETURN_TIMEOUT_MS
                addLog("自动详情停留期间进入陌陌图片浏览页面：保留详情上下文，等待图片页面返回")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
            return
        }

        if (accountBrowseImageBrowserActive) {
            if (accountBrowsePhase == AccountBrowsePhase.PAUSED && accountBrowseManualResumeAllowed) {
                if (activityClass == accountBrowseImageBrowserReturnActivityClass) {
                    accountBrowseImageBrowserActive = false
                    addLog("用户已手动退出图片浏览页面；保持安全暂停，静默结束后仍会重新识别并导航到动态标签")
                } else {
                    addLog("用户暂停期间图片浏览 Activity 已变化：$activityClass；保持安全暂停，交由恢复导航重新判断")
                }
                return
            }
            if (accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB ||
                accountBrowsePhase == AccountBrowsePhase.VERIFY_NAVIGATION_ACTION ||
                accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_FEED_STABLE
            ) {
                if (activityClass == accountBrowseImageBrowserReturnActivityClass) {
                    accountBrowseImageBrowserActive = false
                    accountBrowseImageBrowserReturnActivityClass = ""
                    addLog("恢复导航已退出图片浏览页面并回到详情层级；继续主动寻找动态主页面")
                }
                return
            }
            if (activityClass == accountBrowseImageBrowserReturnActivityClass) {
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
                accountBrowseDeadlineAt = System.currentTimeMillis() + DETAIL_RESTABILIZE_TIMEOUT_MS
                accountBrowseLastStableFingerprint = ""
                accountBrowseStableSamples = 0
                addLog("检测到已退出图片浏览器并返回原动态详情 Activity：$activityClass；正在重新确认详情页面稳定性")
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            } else {
                accountBrowseImageBrowserActive = false
                pauseAccountBrowse("图片浏览器退出后进入了非原动态详情页面：$activityClass；为避免误返回或误点击，已安全暂停")
            }
        }
    }

    private fun waitForImageBrowserReturn() {
        if (!accountBrowseImageBrowserActive) {
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
            accountBrowseDeadlineAt = System.currentTimeMillis() + DETAIL_RESTABILIZE_TIMEOUT_MS
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("等待动态图片浏览页面返回原详情超时；没有执行返回或点击操作")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun waitForDetailRestabilize() {
        val root = obtainTargetRootOrPause() ?: return
        val state = try {
            isMomoDynamicFeedPage(root) to pageFingerprint(root)
        } finally {
            runCatching { root.recycle() }
        }
        val isFeedPage = state.first
        val fingerprint = state.second

        if (accountBrowseCurrentActivityClass != accountBrowseImageBrowserReturnActivityClass) {
            pauseAccountBrowse("图片浏览器返回后的 Activity 与原动态详情不一致；已停止自动操作")
            return
        }
        if (isFeedPage) {
            pauseAccountBrowse("图片浏览器退出后已回到动态列表而非原详情页；保留暂停状态，等待手动重新进入动态")
            return
        }

        if (fingerprint.nodeCount >= MIN_DETAIL_NODE_COUNT &&
            fingerprint.signature != accountBrowseSourceFingerprint
        ) {
            if (fingerprint.signature == accountBrowseLastStableFingerprint) {
                accountBrowseStableSamples++
            } else {
                accountBrowseLastStableFingerprint = fingerprint.signature
                accountBrowseStableSamples = 1
            }

            if (accountBrowseStableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
                accountBrowseDetailFingerprint = fingerprint.signature
                accountBrowseImageBrowserActive = false
                accountBrowseDetailDwellDueAt = System.currentTimeMillis() + accountBrowseDetailDwellRemainingMs
                accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
                val remaining = accountBrowseDetailDwellRemainingMs
                addLog("原动态详情页面已连续 ${REQUIRED_STABLE_PAGE_SAMPLES} 次确认稳定；已更新页面指纹，继续剩余阅读时间 ${remaining / 1000.0} 秒")
                scheduleAccountBrowseStep(remaining.coerceAtLeast(300L))
                return
            }
        } else {
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse("图片浏览器已退出，但原动态详情页面未能在限定时间内稳定；已安全暂停")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    /**
     * Some devices do not deliver global touch-interaction events to this service.
     * In that case, infer manual interaction from real target-app click/scroll/input events.
     */
    private fun currentMonitorPageContext(): String {
        val snapshot = MonitorStore.snapshot(this)
        return "当前识别应用=${snapshot.app}；当前识别页面=${snapshot.page}"
    }

    private fun isTrackedInteractionEvent(eventType: Int): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED

    /** True only when the current event matches an action Freedom itself submitted. */
    private fun isProgrammaticInteractionEvent(eventType: Int, now: Long): Boolean {
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            (accountBrowseGestureInFlight || accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE)
        ) {
            return true
        }
        if (now >= accountBrowseIgnoreManualEventsUntil) return false
        return when {
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                (accountBrowseProgrammaticActionLabel.contains("滑动") ||
                    accountBrowseProgrammaticActionLabel.contains("滚动")) -> true
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                accountBrowseProgrammaticActionLabel.contains("ACTION_CLICK") -> true
            else -> false
        }
    }

    private fun isDetailLoadContainerScroll(eventType: Int, className: String): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD &&
            accountBrowseCurrentActivityClass == MOMO_DYNAMIC_DETAIL_ACTIVITY &&
            className.substringAfterLast('.') == "RecyclerView"

    private fun isNavigationVerificationScroll(eventType: Int, className: String): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.VERIFY_NAVIGATION_ACTION &&
            className.substringAfterLast('.') == "RecyclerView"

    private fun classifyInteractionEventOrigin(eventType: Int, now: Long, className: String): String = when {
        isDetailLoadContainerScroll(eventType, className) ->
            "动态详情加载期间 RecyclerView 的容器滚动/布局事件；缺少独立触摸证据，不按用户操作处理"
        isNavigationVerificationScroll(eventType, className) ->
            "导航动作后的页面切换/列表加载事件；正在验证页面层级，单独的 RecyclerView 滚动不足以认定为用户操作"
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ->
            "Freedom 自动滑动确认阶段的列表事件；仅凭列表事件无法区分系统手势反馈与同期手动滚动，不归类为用户操作"
        isProgrammaticInteractionEvent(eventType, now) ->
            "${accountBrowseProgrammaticActionLabel.ifBlank { "Freedom 自动动作" }}的系统回调；不按手动操作处理"
        else ->
            "目标应用交互事件；未匹配到 Freedom 对应的自动动作窗口，作为用户手动操作候选"
    }

    /** Describe the public accessibility source node; bounds are view bounds, not finger coordinates. */
    private fun accessibilitySourceDetails(event: AccessibilityEvent): String {
        val source = runCatching { event.source }.getOrNull()
            ?: return "控件来源节点=系统未提供；控件坐标=系统未提供"
        return try {
            val bounds = Rect()
            runCatching { source.getBoundsInScreen(bounds) }
            val viewId = runCatching { source.viewIdResourceName.orEmpty() }.getOrDefault("")
            val traits = buildList {
                if (runCatching { source.isClickable }.getOrDefault(false)) add("可点击")
                if (runCatching { source.isLongClickable }.getOrDefault(false)) add("可长按")
                if (runCatching { source.isScrollable }.getOrDefault(false)) add("可滚动")
                if (runCatching { source.isEditable }.getOrDefault(false)) add("可编辑")
            }.joinToString("、")
            buildString {
                append("控件坐标范围=")
                append(if (bounds.isEmpty) "系统未提供" else bounds.toShortString())
                if (viewId.isNotBlank()) append("；控件 ViewId=").append(viewId)
                if (traits.isNotBlank()) append("；控件能力=").append(traits)
                append("；注意：这是控件边界，不是手指触摸坐标")
            }
        } finally {
            runCatching { source.recycle() }
        }
    }

    /**
     * Some devices do not deliver global touch-interaction events to this service.
     * Infer manual interaction from target-app events only when they do not match an
     * accessibility action that Freedom itself just submitted.
     */
    private fun handleManualInteractionFallback(
        eventType: Int,
        className: String,
        content: String,
        description: String,
        event: AccessibilityEvent
    ) {
        if (!isTrackedInteractionEvent(eventType)) return
        val now = System.currentTimeMillis()
        if (isProgrammaticInteractionEvent(eventType, now)) return
        if (isDetailLoadContainerScroll(eventType, className)) {
            addLog("详情页面正在初始化；检测到 RecyclerView 滚动/布局事件，但没有独立用户触摸证据，来源标记为待确认，不触发暂停")
            return
        }
        if (isNavigationVerificationScroll(eventType, className)) {
            addLog("导航页面正在切换或加载；检测到 RecyclerView 滚动事件，但没有独立用户触摸证据，暂不触发暂停")
            return
        }
        if (accountBrowseImageBrowserActive ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
        ) return

        val eventName = rawEventName(eventType)
        val target = sequenceOf(content, description, className).firstOrNull { it.isNotBlank() }
            ?.take(120) ?: "未提供控件文本"
        val globalTouchRecent = now - accountBrowseLastGlobalTouchEventAt <= GLOBAL_TOUCH_EVENT_ASSOCIATION_MS
        val touchEvidence = if (globalTouchRecent) {
            "附近收到全局触摸事件=$accountBrowseLastGlobalTouchEventName；事件来源=${accountBrowseLastGlobalTouchEventOrigin.ifBlank { "未归类" }}"
        } else {
            "附近未收到 TYPE_TOUCH_INTERACTION_START/END；本次按目标应用控件事件推断用户操作，系统没有提供手指坐标"
        }
        val sourceDetails = accessibilitySourceDetails(event)
        val reason = "检测到用户在操作屏幕（目标应用事件：$eventName；控件：${className.substringAfterLast('.')}；内容：$target）"

        addLog(
            "$reason；Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；" +
                "自动浏览阶段=${accountBrowsePhase.name}；${currentMonitorPageContext()}；$touchEvidence；$sourceDetails"
        )
        if (accountBrowsePhase != AccountBrowsePhase.PAUSED) {
            pauseAccountBrowse(
                "$reason；自动浏览暂时让出操作权",
                allowManualResume = true
            )
        } else {
            accountBrowseManualResumeAllowed = true
            if (accountBrowsePausedContext == null) {
                accountBrowsePausedContext = ManualResumeContext(
                    phaseName = "PAUSED",
                    activityClass = accountBrowseCurrentActivityClass,
                    sourceFingerprint = accountBrowseSourceFingerprint,
                    detailFingerprint = accountBrowseDetailFingerprint,
                    imageBrowserActive = accountBrowseImageBrowserActive,
                    lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                    capturedAt = now
                )
            }
            addLog("用户操作发生时自动浏览已处于安全暂停；原暂停原因=${accountBrowsePauseReason.ifBlank { "未记录" }}；静默结束后重新分析页面层级并导航到动态标签")
        }

        mainHandler.removeCallbacks(manualTouchResumeRunnable)
        if (accountBrowseTouchActive) {
            addLog("目标控件事件发生时全局触摸仍处于活动状态；暂不启动静默倒计时，等待 TYPE_TOUCH_INTERACTION_END")
        } else {
            addLog("已记录目标控件交互；最后一次操作后等待 ${MANUAL_TOUCH_RESUME_MS / 1000.0} 秒静默，再重新识别当前页面")
            mainHandler.postDelayed(manualTouchResumeRunnable, MANUAL_TOUCH_RESUME_MS)
        }
    }

    private fun checkFeedListReturned() {
        val root = obtainTargetRootOrPause() ?: return
        val returnState = try {
            if (!isMomoDynamicFeedPage(root)) {
                false to ""
            } else {
                val listNode = findScrollableFeedContainer(root)
                if (listNode == null) {
                    true to ""
                } else {
                    try {
                        true to captureVisibleFeedNodeSnapshot(listNode)
                    } finally {
                        runCatching { listNode.recycle() }
                    }
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
        val isFeedList = returnState.first
        val currentSnapshot = returnState.second

        if (isFeedList && isFeedPositionRestored(accountBrowseSourceSnapshot, currentSnapshot)) {
            if (currentSnapshot == accountBrowseLastReturnedSnapshot) {
                accountBrowseReturnedStableSamples++
            } else {
                accountBrowseLastReturnedSnapshot = currentSnapshot
                accountBrowseReturnedStableSamples = 1
            }

            if (accountBrowseReturnedStableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_SCROLL
                accountBrowseReturnedStableSamples = 0
                accountBrowseLastReturnedSnapshot = ""
                accountBrowseReturnMismatchLogged = false
                val delay = randomScrollIntervalMs()
                addLog(
                    "已返回原动态列表，位置快照连续 ${REQUIRED_STABLE_PAGE_SAMPLES} 次匹配；" +
                        "下一轮滑动将在随机等待 ${delay / 1000.0} 秒后进行"
                )
                scheduleAccountBrowseStep(delay)
                return
            }
        } else if (isFeedList) {
            accountBrowseReturnedStableSamples = 0
            accountBrowseLastReturnedSnapshot = ""
            if (!accountBrowseReturnMismatchLogged) {
                addLog(
                    "已识别动态列表，但当前可见内容与打开动态前的位置快照不匹配；" +
                        "暂不继续滑动，等待列表位置恢复并稳定"
                )
                accountBrowseReturnMismatchLogged = true
            }
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            pauseAccountBrowse(
                if (isFeedList) {
                    "已返回动态列表，但无法确认恢复到打开动态前的位置；为避免跳过内容，已暂停"
                } else {
                    "返回后没有重新识别到动态列表；请检查页面并重新启动"
                }
            )
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    /** A return is accepted only when several list-specific visible nodes and bounds match. */
    private fun isFeedPositionRestored(
        expectedSnapshot: String,
        currentSnapshot: String
    ): Boolean {
        if (expectedSnapshot.isBlank() || currentSnapshot.isBlank()) return false
        val expected = expectedSnapshot.split(" | ").filter { it.isNotBlank() }.toSet()
        val current = currentSnapshot.split(" | ").filter { it.isNotBlank() }.toSet()
        val comparableCount = minOf(expected.size, current.size)
        if (comparableCount < MIN_RETURN_SNAPSHOT_MATCH_LINES) return false
        val requiredMatches = maxOf(
            MIN_RETURN_SNAPSHOT_MATCH_LINES,
            kotlin.math.ceil(comparableCount * RETURN_SNAPSHOT_MATCH_RATIO).toInt()
        )
        return expected.intersect(current).size >= requiredMatches
    }

    private fun isMomoDynamicFeedPage(root: AccessibilityNodeInfo): Boolean =
        inspectMomoFeedReadiness(root).fingerprint != null

    /**
     * Momo's target feed is the top-level Home page's inner "动态" tab.
     * The bottom navigation's "首页" is a different navigation level and must not
     * be used as the feed-tab marker. We identify the top tab row by its visible
     * labels (动态 / 附近 / AI小陌), then validate a visible list below that row.
     */
    private fun inspectMomoFeedReadiness(root: AccessibilityNodeInfo): FeedReadinessInspection {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName != MOMO_PACKAGE) {
            return FeedReadinessInspection(null, "当前窗口不是陌陌：$packageName")
        }

        val tabInspection = inspectDynamicTabRow(root)
        val tabEvidence = tabInspection.evidence
            ?: return FeedReadinessInspection(null, tabInspection.reason)
        if (tabEvidence.selectionKnown && !tabEvidence.dynamicSelected) {
            return FeedReadinessInspection(null, "首页顶部当前选中项不是‘动态’；${tabEvidence.diagnostic}")
        }

        val listNode = findVisibleFeedContainer(root, tabEvidence.contentTop)
            ?: return FeedReadinessInspection(null, "已确认首页顶部动态标签行，但下方没有找到足够大的可见列表容器")

        return try {
            val bounds = Rect()
            listNode.getBoundsInScreen(bounds)
            val metrics = resources.displayMetrics
            val minWidth = (metrics.widthPixels * MIN_LIST_WIDTH_RATIO).toInt()
            val visibleContentTop = maxOf(bounds.top, tabEvidence.contentTop)
            val visibleContentHeight = bounds.bottom - visibleContentTop
            val minHeight = (metrics.heightPixels * MIN_LIST_HEIGHT_RATIO).toInt()

            if (!listNode.isVisibleToUser) {
                return FeedReadinessInspection(null, "列表容器存在，但当前不可见")
            }
            if (bounds.width() < minWidth || visibleContentHeight < minHeight) {
                return FeedReadinessInspection(
                    null,
                    "列表有效区域尺寸不足：宽=${bounds.width()}，有效高=$visibleContentHeight"
                )
            }

            val contentBounds = Rect(bounds)
            contentBounds.top = visibleContentTop
            var visibleChildren = 0
            val childCount = runCatching { listNode.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { listNode.getChild(index) }.getOrNull() ?: continue
                try {
                    val childBounds = Rect()
                    child.getBoundsInScreen(childBounds)
                    if (child.isVisibleToUser && childBounds.width() > 0 && childBounds.height() > 0 &&
                        Rect.intersects(contentBounds, childBounds)
                    ) {
                        visibleChildren++
                    }
                } finally {
                    runCatching { child.recycle() }
                }
            }
            if (visibleChildren == 0) {
                return FeedReadinessInspection(null, "列表容器和尺寸有效，但当前没有可见列表项目")
            }

            val className = listNode.className?.toString().orEmpty().substringAfterLast('.')
            val signature = listOf(
                className,
                bounds.left / FINGERPRINT_SIZE_BUCKET_PX,
                visibleContentTop / FINGERPRINT_SIZE_BUCKET_PX,
                bounds.width() / FINGERPRINT_SIZE_BUCKET_PX,
                visibleContentHeight / FINGERPRINT_SIZE_BUCKET_PX,
                when {
                    visibleChildren <= 1 -> 1
                    visibleChildren <= 3 -> 2
                    visibleChildren <= 6 -> 3
                    else -> 4
                }
            ).joinToString(":")
            FeedReadinessInspection(
                PageFingerprint(signature = signature, nodeCount = visibleChildren),
                "${tabEvidence.diagnostic}；列表=$className，当前可见项目=$visibleChildren"
            )
        } finally {
            runCatching { listNode.recycle() }
        }
    }


    /**
     * Locate the upper horizontal tab row. Resource IDs are deliberately not required:
     * Momo versions may expose different IDs or omit IDs from accessibility nodes.
     * If another tab is selected, its state and the Dynamic label bounds are both retained
     * so the caller can actively click Dynamic. If selection semantics are unavailable,
     * the same-row navigation labels provide a bounded structural fallback.
     */
    private fun inspectDynamicTabRow(root: AccessibilityNodeInfo): DynamicTabInspection {
        val metrics = resources.displayMetrics
        val upperBandBottom = (metrics.heightPixels * DYNAMIC_TAB_UPPER_BAND_RATIO).toInt()
        val rowTolerance = maxOf(dp(DYNAMIC_TAB_ROW_TOLERANCE_DP), (metrics.heightPixels * 0.04f).toInt())
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to false)
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val (node, selectedInPath) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in DYNAMIC_TAB_LABELS -> text
                        description in DYNAMIC_TAB_LABELS -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.top >= 0 && bounds.centerY() <= upperBandBottom
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }

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
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        val dynamicLabels = labels.filter { it.label == MOMO_DYNAMIC_TAB_LABEL }
        val nearbyLabels = labels.filter { it.label == MOMO_NEARBY_TAB_LABEL }
        val aiLabels = labels.filter { it.label == MOMO_AI_TAB_LABEL }
        if (dynamicLabels.isEmpty()) {
            return DynamicTabInspection(null, "屏幕顶部区域没有识别到‘动态’标签")
        }

        var bestRow: List<TabLabelEvidence>? = null
        var bestScore = -1
        for (dynamic in dynamicLabels) {
            val sameRowNearby = nearbyLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }
            val sameRowAi = aiLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }

            // The photo shows these three labels in one horizontal row. Requiring at least
            // one neighbor avoids matching unrelated "动态" text elsewhere in the page.
            if (sameRowNearby != null || sameRowAi != null) {
                val row = listOfNotNull(dynamic, sameRowNearby, sameRowAi)
                val score = (if (sameRowNearby != null) 1 else 0) + (if (sameRowAi != null) 1 else 0)
                if (score > bestScore) {
                    bestRow = row
                    bestScore = score
                }
            }
        }

        val row = bestRow ?: return DynamicTabInspection(
            null,
            "识别到顶部‘动态’，但未找到同一行的‘附近’或‘AI小陌’标签，无法确认是首页顶部导航"
        )
        val dynamic = row.first { it.label == MOMO_DYNAMIC_TAB_LABEL }
        val selectedLabels = row.filter { it.selected }.map { it.label }.distinct()
        val selectionKnown = selectedLabels.size == 1
        val dynamicSelected = selectionKnown && selectedLabels.single() == MOMO_DYNAMIC_TAB_LABEL

        val rowBottom = row.maxOf { it.bounds.bottom }
        val contentTop = rowBottom + dp(DYNAMIC_TAB_CONTENT_GAP_DP)
        val selectionDescription = when {
            dynamicSelected -> "顶部‘动态’标签已选中"
            selectionKnown -> "顶部导航当前选中项为‘${selectedLabels.single()}’；可以主动切换到‘动态’"
            selectedLabels.size > 1 -> "Tab选中状态存在祖先节点歧义，按顶部标签行结构识别"
            else -> "无障碍节点未暴露Tab选中状态，按‘动态/附近/AI小陌’同排结构识别"
        }
        val neighborDescription = if (bestScore == 2) "附近与AI小陌同排" else "附近或AI小陌同排"
        return DynamicTabInspection(
            DynamicTabRowEvidence(
                contentTop = contentTop,
                diagnostic = "$selectionDescription，$neighborDescription",
                dynamicBounds = Rect(dynamic.bounds),
                dynamicSelected = dynamicSelected,
                selectionKnown = selectionKnown
            ),
            ""
        )
    }

    /** Finds a visible list-like container below the confirmed top Dynamic-tab row. */
    private fun findVisibleFeedContainer(
        root: AccessibilityNodeInfo,
        contentTop: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val metrics = resources.displayMetrics
        val minWidth = (metrics.widthPixels * MIN_LIST_WIDTH_RATIO).toInt()
        val minHeight = (metrics.heightPixels * MIN_LIST_HEIGHT_RATIO).toInt()
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < PAGE_FINGERPRINT_MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val visibleTop = maxOf(bounds.top, contentTop)
                    val visibleHeight = bounds.bottom - visibleTop
                    val className = node.className?.toString().orEmpty()
                    val isListLike = className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") || className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") || className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")
                    if (node.isVisibleToUser && isListLike && bounds.width() >= minWidth &&
                        visibleHeight >= minHeight
                    ) {
                        val area = bounds.width().toLong() * visibleHeight.toLong()
                        if (area > bestArea) {
                            runCatching { bestNode?.recycle() }
                            bestNode = AccessibilityNodeInfo.obtain(node)
                            bestArea = area
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= PAGE_FINGERPRINT_MAX_NODES) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return bestNode
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

    /** Random pause between confirmed feed swipes. Both endpoints are inclusive. */
    private fun randomScrollIntervalMs(): Long =
        Random.nextLong(MIN_SCROLL_INTERVAL_MS, MAX_SCROLL_INTERVAL_MS + 1L)

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
        GestureTrailOverlay.clear()
        restoreMonitorOverlayTouchPassthrough()
        mainHandler.removeCallbacksAndMessages(null)
        scanQueued = false
        accountBrowseStepPending = false
        hideFirstSwipeCountdown()
        if (targetReceiverRegistered) {
            runCatching { unregisterReceiver(targetChangedReceiver) }
            targetReceiverRegistered = false
        }
        if (refreshReceiverRegistered) {
            runCatching { unregisterReceiver(refreshReceiver) }
            refreshReceiverRegistered = false
        }
        if (gestureCalibrationReceiverRegistered) {
            runCatching { unregisterReceiver(gestureCalibrationReceiver) }
            gestureCalibrationReceiverRegistered = false
        }
        gestureCalibrationInProgress = false
        gestureCalibrationBounds = null
        mainHandler.removeCallbacks(gestureCalibrationRunnable)
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DEBOUNCE_MS = 350L

        private const val ACCOUNT_BROWSE_PREFS = "freedom_main_ui"
        private const val ACCOUNT_BROWSE_KEY = "keep_account_mode_enabled"
        private const val KEY_BROWSE_GESTURE_DURATION_MS = "account_browse_gesture_duration_ms"
        private const val KEY_BROWSE_GESTURE_CURVE_OFFSET = "account_browse_gesture_curve_offset"
        private const val MOMO_PACKAGE = "com.immomo.momo"
        private const val MOMO_DYNAMIC_TAB_LABEL = "动态"
        private const val MOMO_NEARBY_TAB_LABEL = "附近"
        private const val MOMO_AI_TAB_LABEL = "AI小陌"
        private const val MOMO_MAIN_ACTIVITY = "com.immomo.momo.maintab.MaintabActivity"
        private const val MOMO_HOME_TAB_LABEL = "首页"
        private val MOMO_HOME_NAV_LABELS = setOf("首页", "附近", "消息", "我的", "直播", "动态")
        private const val HOME_NAV_LOWER_BAND_RATIO = 0.72f
        private val DYNAMIC_TAB_LABELS = setOf(
            MOMO_DYNAMIC_TAB_LABEL,
            MOMO_NEARBY_TAB_LABEL,
            MOMO_AI_TAB_LABEL
        )
        private const val DYNAMIC_TAB_UPPER_BAND_RATIO = 0.28f
        private const val DYNAMIC_TAB_ROW_TOLERANCE_DP = 48
        private const val DYNAMIC_TAB_CONTENT_GAP_DP = 8
        private const val TAB_TARGET_MATCH_TOLERANCE_DP = 18
        private const val MAX_TAB_CLICK_TARGET_HEIGHT_RATIO = 0.24f
        private const val MAX_TAB_CLICK_ANCESTOR_DEPTH = 5
        private const val NAV_ACTION_DYNAMIC_TAB = "DYNAMIC_TAB"
        private const val NAV_ACTION_HOME_TAB = "HOME_TAB"
        private const val NAV_ACTION_BACK = "BACK"
        private const val NAVIGATION_ACTION_EVENT_WINDOW_MS = 1_800L
        private const val NAVIGATION_ACTION_VERIFY_TIMEOUT_MS = 2_100L
        private const val NAVIGATION_ACTION_VERIFY_POLL_MS = 350L
        private const val NAVIGATION_ACTION_RETRY_DELAY_MS = 700L
        private const val MAX_RECOVERY_TAB_CLICK_ATTEMPTS = 2
        private const val MAX_RECOVERY_HOME_CLICK_ATTEMPTS = 2
        private const val MAX_RECOVERY_BACK_STEPS = 4

        private const val MIN_BROWSE_DELAY_MS = 3_000L
        private const val MAX_BROWSE_DELAY_MS = 15_000L
        private const val MIN_SCROLL_INTERVAL_MS = 5_000L
        private const val MAX_SCROLL_INTERVAL_MS = 8_000L
        private const val DETAIL_LOAD_TIMEOUT_MS = 12_000L
        private const val LIST_RETURN_TIMEOUT_MS = 10_000L
        private const val PAGE_STATE_POLL_MS = 300L
        private const val SCROLL_SETTLE_MS = 850L
        private const val CARD_OPEN_SETTLE_MS = 500L
        private const val MONITOR_OVERLAY_PASSTHROUGH_WATCHDOG_MS = 5_000L
        private const val SWIPE_WINDOW_SYNC_DELAY_MS = 120L
        private const val IMAGE_BROWSER_RETURN_TIMEOUT_MS = 60_000L
        private const val DETAIL_RESTABILIZE_TIMEOUT_MS = 10_000L
        private const val MOMO_DYNAMIC_DETAIL_ACTIVITY = "com.immomo.momo.luaview.LuaViewActivity"
        private const val MOMO_FEED_IMAGE_BROWSER_ACTIVITY = "com.immomo.momo.imagefactory.imageborwser.impls.FeedImageBrowserActivity"
        private const val MANUAL_TOUCH_RESUME_MS = 2_500L
        private const val SAFE_CARD_RECHECK_INTERVAL_MS = 2_500L
        private const val MAX_SAFE_CARD_RECHECKS_BEFORE_NEXT_SWIPE = 2
        private const val PROGRAMMATIC_CLICK_EVENT_WINDOW_MS = 900L
        private const val GLOBAL_TOUCH_EVENT_ASSOCIATION_MS = 1_500L
        private const val INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS = 60_000L
        private const val INITIAL_FEED_STABILITY_TIMEOUT_MS = 10_000L
        private const val REQUIRED_STABLE_FEED_SAMPLES = 3
        private const val FIRST_SWIPE_COUNTDOWN_SECONDS = 3
        private const val FIRST_SWIPE_COUNTDOWN_STEP_MS = 1_000L
        private const val REQUIRED_STABLE_PAGE_SAMPLES = 2
        private const val MIN_RETURN_SNAPSHOT_MATCH_LINES = 3
        private const val RETURN_SNAPSHOT_MATCH_RATIO = 0.70
        private const val SNAPSHOT_MAX_NODES = 500
        private const val SNAPSHOT_MAX_TEXT_NODES = 8
        private const val SNAPSHOT_TEXT_LENGTH = 36
        private const val SNAPSHOT_MAX_LOG_LENGTH = 520
        private const val SNAPSHOT_TOP_RATIO = 0.18f
        private const val SNAPSHOT_BOTTOM_RATIO = 0.92f
        private const val MIN_DETAIL_NODE_COUNT = 12
        private const val PAGE_FINGERPRINT_MAX_NODES = 500
        private const val FINGERPRINT_SIZE_BUCKET_PX = 24

        private const val DEFAULT_GESTURE_DURATION_MS = 620L
        private const val MIN_GESTURE_DURATION_MS = 350L
        private const val MAX_GESTURE_DURATION_MS = 1_200L
        private const val DEFAULT_CURVE_OFFSET_RATIO = 0.012f
        private const val MAX_CURVE_OFFSET_RATIO = 0.04f
        private const val MIN_GESTURE_WIDTH_PX = 220
        private const val MIN_GESTURE_HEIGHT_PX = 300
        private const val GESTURE_PATH_STEPS = 32
        private const val ENABLE_SCROLL_MICRO_PERTURBATION = true
        private const val MICRO_PERTURBATION_AMPLITUDE_PX = 1.2f
        private const val MICRO_PERTURBATION_PATH_STEPS = 100
        private const val MIN_FEED_CARD_WIDTH_RATIO = 0.75f
        private const val MIN_FEED_CARD_HEIGHT_RATIO = 0.18f

        private const val MIN_LIST_WIDTH_RATIO = 0.58f
        private const val MIN_LIST_HEIGHT_RATIO = 0.28f
        private const val MIN_COMPOSER_WIDTH_RATIO = 0.35f
        private const val COMPOSER_BOTTOM_RATIO = 0.62f

        private const val LOG_DETAIL_SEPARATOR = "\u001F"
    }
}
