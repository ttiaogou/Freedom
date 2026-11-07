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
import com.freedom.BuildConfig
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.accessibility.browse.MomoDynamicCardSelector
import com.freedom.accessibility.browse.MomoFeedGestureController
import com.freedom.accessibility.browse.MomoPageInspector
import com.freedom.accessibility.browse.MomoPopupInspector
import com.freedom.accessibility.browse.MomoNavigationController
import com.freedom.accessibility.browse.AccountBrowseController
import com.freedom.accessibility.browse.AccountBrowsePhase
import com.freedom.accessibility.browse.AccountBrowseSessionState
import com.freedom.accessibility.browse.DetailPageIdentityInspection
import com.freedom.accessibility.browse.DetailDwellScrollPolicy
import com.freedom.accessibility.browse.DynamicTabStartupScrollPolicy
import com.freedom.accessibility.browse.DynamicTabInspection
import com.freedom.accessibility.browse.DynamicTabRowEvidence
import com.freedom.accessibility.browse.FeedGestureTarget
import com.freedom.accessibility.browse.FeedPositionMatcher
import com.freedom.accessibility.browse.FeedReturnStability
import com.freedom.accessibility.browse.FeedReturnVerificationPolicy
import com.freedom.accessibility.browse.FeedRefreshVerificationPolicy
import com.freedom.accessibility.browse.FeedReadinessInspection
import com.freedom.accessibility.browse.HomeBottomNavEvidence
import com.freedom.accessibility.browse.ManualResumeContext
import com.freedom.accessibility.browse.PageFingerprint
import com.freedom.accessibility.browse.PopupInspection
import com.freedom.accessibility.browse.ScrollGesturePlan
import com.freedom.accessibility.browse.ScrollGestureProfile
import com.freedom.accessibility.event.AccessibilityEventFormatter
import com.freedom.accessibility.scan.AccessibilityTreeScanner
import com.freedom.accessibility.scheduling.AccountBrowseScheduler
import com.freedom.accessibility.gesture.GestureTuning
import com.freedom.accessibility.gesture.GestureCalibrationController
import com.freedom.overlay.GestureTrailOverlay
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class FreedomAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEventSignature = ""
    private var lastEventAt = 0L
    private var scanQueued = false
    private var targetReceiverRegistered = false
    private var debugRecoveryTestReceiverRegistered = false

    private var accountBrowsePhase: AccountBrowsePhase
        get() = accountBrowseController.phase
        set(value) {
            if (value == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB &&
                accountBrowseController.phase != AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            ) {
                accountBrowseSession.dynamicTabWaitStartedAt = System.currentTimeMillis()
            }
            accountBrowseController.transitionTo(value)
        }

    // Compatibility accessors keep the existing Service methods unchanged; backing
    // session values are owned by AccountBrowseController.session.
    private val accountBrowseSession: AccountBrowseSessionState
        get() = accountBrowseController.session

    private var accountBrowsePauseReason: String
        get() = accountBrowseSession.pauseReason
        set(value) { accountBrowseSession.pauseReason = value }

    private var accountBrowseLastDynamicTabWaitReason: String
        get() = accountBrowseSession.lastDynamicTabWaitReason
        set(value) { accountBrowseSession.lastDynamicTabWaitReason = value }

    private var accountBrowseDeadlineAt: Long
        get() = accountBrowseSession.deadlineAt
        set(value) { accountBrowseSession.deadlineAt = value }

    private var accountBrowseSourceFingerprint: String
        get() = accountBrowseSession.sourceFingerprint
        set(value) { accountBrowseSession.sourceFingerprint = value }

    private var accountBrowseDetailFingerprint: String
        get() = accountBrowseSession.detailFingerprint
        set(value) { accountBrowseSession.detailFingerprint = value }

    private var accountBrowseDetailActivityClass: String
        get() = accountBrowseSession.detailActivityClass
        set(value) { accountBrowseSession.detailActivityClass = value }

    private var accountBrowseLastStableFingerprint: String
        get() = accountBrowseSession.lastStableFingerprint
        set(value) { accountBrowseSession.lastStableFingerprint = value }

    private var accountBrowseStableSamples: Int
        get() = accountBrowseSession.stableSamples
        set(value) { accountBrowseSession.stableSamples = value }

    private var accountBrowseTouchActive: Boolean
        get() = accountBrowseSession.touchActive
        set(value) { accountBrowseSession.touchActive = value }

    private var accountBrowseManualResumeAllowed: Boolean
        get() = accountBrowseSession.manualResumeAllowed
        set(value) { accountBrowseSession.manualResumeAllowed = value }

    private var accountBrowseTouchStartedDuringGesture: Boolean
        get() = accountBrowseSession.touchStartedDuringGesture
        set(value) { accountBrowseSession.touchStartedDuringGesture = value }

    private var accountBrowseTouchEndedDuringGesture: Boolean
        get() = accountBrowseSession.touchEndedDuringGesture
        set(value) { accountBrowseSession.touchEndedDuringGesture = value }

    private var accountBrowseGestureInFlight: Boolean
        get() = accountBrowseSession.gestureInFlight
        set(value) { accountBrowseSession.gestureInFlight = value }

    private var accountBrowseScrollEventSeen: Boolean
        get() = accountBrowseSession.scrollEventSeen
        set(value) { accountBrowseSession.scrollEventSeen = value }

    private var accountBrowseScrollEventCount: Int
        get() = accountBrowseSession.scrollEventCount
        set(value) { accountBrowseSession.scrollEventCount = value }

    private var accountBrowseLastScrollEventAt: Long
        get() = accountBrowseSession.lastScrollEventAt
        set(value) { accountBrowseSession.lastScrollEventAt = value }

    private var accountBrowseSwipeCount: Int
        get() = accountBrowseSession.swipeCount
        set(value) { accountBrowseSession.swipeCount = value }

    private var accountBrowseFallbackScrollAttempted: Boolean
        get() = accountBrowseSession.fallbackScrollAttempted
        set(value) { accountBrowseSession.fallbackScrollAttempted = value }

    private var accountBrowseSourceSnapshot: String
        get() = accountBrowseSession.sourceSnapshot
        set(value) { accountBrowseSession.sourceSnapshot = value }

    private var accountBrowseReturnedStableSamples: Int
        get() = accountBrowseSession.returnedStableSamples
        set(value) { accountBrowseSession.returnedStableSamples = value }

    private var accountBrowseLastReturnedSnapshot: String
        get() = accountBrowseSession.lastReturnedSnapshot
        set(value) { accountBrowseSession.lastReturnedSnapshot = value }

    private var accountBrowseReturnMismatchLogged: Boolean
        get() = accountBrowseSession.returnMismatchLogged
        set(value) { accountBrowseSession.returnMismatchLogged = value }

    private var accountBrowseCountdownRemaining: Int
        get() = accountBrowseSession.countdownRemaining
        set(value) { accountBrowseSession.countdownRemaining = value }

    private var accountBrowseOverlayPassthroughActive: Boolean
        get() = accountBrowseSession.overlayPassthroughActive
        set(value) { accountBrowseSession.overlayPassthroughActive = value }

    private var accountBrowseCurrentActivityClass: String
        get() = accountBrowseSession.currentActivityClass
        set(value) { accountBrowseSession.currentActivityClass = value }

    private var accountBrowseImageBrowserReturnActivityClass: String
        get() = accountBrowseSession.imageBrowserReturnActivityClass
        set(value) { accountBrowseSession.imageBrowserReturnActivityClass = value }

    private var accountBrowseImageBrowserActive: Boolean
        get() = accountBrowseSession.imageBrowserActive
        set(value) { accountBrowseSession.imageBrowserActive = value }

    private var accountBrowseDetailDwellDueAt: Long
        get() = accountBrowseSession.detailDwellDueAt
        set(value) { accountBrowseSession.detailDwellDueAt = value }

    private var accountBrowseDetailDwellStartedAt: Long
        get() = accountBrowseSession.detailDwellStartedAt
        set(value) { accountBrowseSession.detailDwellStartedAt = value }

    private var accountBrowseDetailDwellRemainingMs: Long
        get() = accountBrowseSession.detailDwellRemainingMs
        set(value) { accountBrowseSession.detailDwellRemainingMs = value }

    private var accountBrowseIgnoreManualEventsUntil: Long
        get() = accountBrowseSession.ignoreManualEventsUntil
        set(value) { accountBrowseSession.ignoreManualEventsUntil = value }

    private var accountBrowseProgrammaticActionLabel: String
        get() = accountBrowseSession.programmaticActionLabel
        set(value) { accountBrowseSession.programmaticActionLabel = value }

    private var accountBrowseSafeCardRecheckCount: Int
        get() = accountBrowseSession.safeCardRecheckCount
        set(value) { accountBrowseSession.safeCardRecheckCount = value }

    private var accountBrowseLastGlobalTouchEventAt: Long
        get() = accountBrowseSession.lastGlobalTouchEventAt
        set(value) { accountBrowseSession.lastGlobalTouchEventAt = value }

    private var accountBrowseLastGlobalTouchEventName: String
        get() = accountBrowseSession.lastGlobalTouchEventName
        set(value) { accountBrowseSession.lastGlobalTouchEventName = value }

    private var accountBrowseLastGlobalTouchEventOrigin: String
        get() = accountBrowseSession.lastGlobalTouchEventOrigin
        set(value) { accountBrowseSession.lastGlobalTouchEventOrigin = value }

    private var accountBrowsePausedContext: ManualResumeContext?
        get() = accountBrowseSession.pausedContext
        set(value) { accountBrowseSession.pausedContext = value }

    private var accountBrowseNavigationActionKey: String
        get() = accountBrowseSession.navigationActionKey
        set(value) { accountBrowseSession.navigationActionKey = value }

    private var accountBrowseNavigationBeforeActivity: String
        get() = accountBrowseSession.navigationBeforeActivity
        set(value) { accountBrowseSession.navigationBeforeActivity = value }

    private var accountBrowseNavigationBeforeFingerprint: String
        get() = accountBrowseSession.navigationBeforeFingerprint
        set(value) { accountBrowseSession.navigationBeforeFingerprint = value }

    private var accountBrowseNavigationActionDeadlineAt: Long
        get() = accountBrowseSession.navigationActionDeadlineAt
        set(value) { accountBrowseSession.navigationActionDeadlineAt = value }

    private var accountBrowseNavigationActionAttempts: Int
        get() = accountBrowseSession.navigationActionAttempts
        set(value) { accountBrowseSession.navigationActionAttempts = value }

    private var accountBrowseRecoveryBackAttempts: Int
        get() = accountBrowseSession.recoveryBackAttempts
        set(value) { accountBrowseSession.recoveryBackAttempts = value }

    private var accountBrowseRecoveryHomeClickAttempts: Int
        get() = accountBrowseSession.recoveryHomeClickAttempts
        set(value) { accountBrowseSession.recoveryHomeClickAttempts = value }

    private var accountBrowseRecoveryDynamicClickAttempts: Int
        get() = accountBrowseSession.recoveryDynamicClickAttempts
        set(value) { accountBrowseSession.recoveryDynamicClickAttempts = value }

    private var accountBrowseDynamicTabClickConfirmed: Boolean
        get() = accountBrowseSession.dynamicTabClickConfirmed
        set(value) { accountBrowseSession.dynamicTabClickConfirmed = value }

    private var accountBrowseFeedRefreshRecoveryActive: Boolean
        get() = accountBrowseSession.feedRefreshRecoveryActive
        set(value) { accountBrowseSession.feedRefreshRecoveryActive = value }

    private var accountBrowseFeedRefreshAttempts: Int
        get() = accountBrowseSession.feedRefreshAttempts
        set(value) { accountBrowseSession.feedRefreshAttempts = value }

    private var accountBrowseFeedRefreshBeforeSnapshot: String
        get() = accountBrowseSession.feedRefreshBeforeSnapshot
        set(value) { accountBrowseSession.feedRefreshBeforeSnapshot = value }

    private var accountBrowseFeedRefreshLastSnapshot: String
        get() = accountBrowseSession.feedRefreshLastSnapshot
        set(value) { accountBrowseSession.feedRefreshLastSnapshot = value }

    private var accountBrowseFeedRefreshStableSamples: Int
        get() = accountBrowseSession.feedRefreshStableSamples
        set(value) { accountBrowseSession.feedRefreshStableSamples = value }

    private var accountBrowseFeedRefreshRecyclerEvents: Int
        get() = accountBrowseSession.feedRefreshRecyclerContentChangeEvents
        set(value) { accountBrowseSession.feedRefreshRecyclerContentChangeEvents = value }

    private var accountBrowseFeedRefreshActionAt: Long
        get() = accountBrowseSession.feedRefreshActionAt
        set(value) { accountBrowseSession.feedRefreshActionAt = value }

    private var accountBrowseFeedRefreshDeadlineAt: Long
        get() = accountBrowseSession.feedRefreshVerificationDeadlineAt
        set(value) { accountBrowseSession.feedRefreshVerificationDeadlineAt = value }

    private var accountBrowseFeedRefreshWaitReason: String
        get() = accountBrowseSession.feedRefreshWaitReason
        set(value) { accountBrowseSession.feedRefreshWaitReason = value }

    private var accountBrowseNotTargetForegroundLogged: Boolean
        get() = accountBrowseSession.notTargetForegroundLogged
        set(value) { accountBrowseSession.notTargetForegroundLogged = value }

    private var accountBrowseRootUnavailableLogged: Boolean
        get() = accountBrowseSession.rootUnavailableLogged
        set(value) { accountBrowseSession.rootUnavailableLogged = value }

    private var accountBrowseLastPopupSignature: String
        get() = accountBrowseSession.lastPopupSignature
        set(value) { accountBrowseSession.lastPopupSignature = value }

    private var accountBrowsePopupDismissPending: Boolean
        get() = accountBrowseSession.popupDismissPending
        set(value) { accountBrowseSession.popupDismissPending = value }

    private var accountBrowsePopupDismissDeadlineAt: Long
        get() = accountBrowseSession.popupDismissDeadlineAt
        set(value) { accountBrowseSession.popupDismissDeadlineAt = value }

    private var accountBrowsePopupNextAttemptAt: Long
        get() = accountBrowseSession.popupNextAttemptAt
        set(value) { accountBrowseSession.popupNextAttemptAt = value }

    private var accountBrowsePopupActionAttempts: Int
        get() = accountBrowseSession.popupActionAttempts
        set(value) { accountBrowseSession.popupActionAttempts = value }

    private var accountBrowsePopupExhaustedLogged: Boolean
        get() = accountBrowseSession.popupExhaustedLogged
        set(value) { accountBrowseSession.popupExhaustedLogged = value }

    // Owns the automatic-browse phase, phase dispatch, session evidence, and lifecycle coordination.
    // Android-specific operations and platform side effects remain in this service.
    private val accountBrowseController by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseController(
            isSessionEnabled = {
                MonitorStore.isRunning(this) && isAccountBrowseModeEnabled()
            },
            isTouchActive = { accountBrowseTouchActive },
            onResetSession = ::resetAccountBrowseSession,
            actions = AccountBrowseController.Actions(
                waitForDynamicTab = ::waitForMomoDynamicTab,
                verifyNavigationAction = ::verifyNavigationAction,
                refreshDynamicFeed = ::refreshDynamicFeed,
                verifyDynamicFeedRefresh = ::verifyDynamicFeedRefresh,
                waitForFeedStable = ::waitForMomoFeedStable,
                runFirstSwipeCountdown = ::runFirstSwipeCountdown,
                performFeedScroll = ::performFeedScroll,
                verifyInitialSwipe = ::verifyInitialSwipe,
                openVisibleFeedCard = ::openVisibleFeedCard,
                checkDetailPageLoaded = ::checkDetailPageLoaded,
                returnFromDetailPage = ::returnFromDetailPage,
                waitForImageBrowserReturn = ::waitForImageBrowserReturn,
                waitForDetailRestabilize = ::waitForDetailRestabilize,
                checkFeedListReturned = ::checkFeedListReturned
            )
        )
    }

    // The extracted scheduler preserves the former Handler Runnable contract:
    // one pending step at a time, no replacement, and no second eligibility check
    // between enqueue and callback dispatch. AccountBrowseController dispatches each phase.
    private val accountBrowseScheduler by lazy(LazyThreadSafetyMode.NONE) {
        AccountBrowseScheduler(
            handler = mainHandler,
            canSchedule = {
                MonitorStore.isRunning(this) &&
                    isAccountBrowseModeEnabled() &&
                    !accountBrowseTouchActive &&
                    accountBrowsePhase != AccountBrowsePhase.PAUSED &&
                    accountBrowsePhase != AccountBrowsePhase.IDLE
            },
            onStep = { performAccountBrowseStep() }
        )
    }

    private val momoPageInspector by lazy(LazyThreadSafetyMode.NONE) {
        MomoPageInspector(
            context = this,
            dp = ::dp,
            config = MomoPageInspector.Config(
                targetPackage = MOMO_PACKAGE,
                detailActivityClass = MOMO_DYNAMIC_DETAIL_ACTIVITY,
                minDetailNodeCount = MIN_DETAIL_NODE_COUNT,
                homeNavLabels = MOMO_HOME_NAV_LABELS,
                homeTabLabel = MOMO_HOME_TAB_LABEL,
                homeNavLowerBandRatio = HOME_NAV_LOWER_BAND_RATIO,
                dynamicTabLabels = DYNAMIC_TAB_LABELS,
                dynamicTabLabel = MOMO_DYNAMIC_TAB_LABEL,
                nearbyTabLabel = MOMO_NEARBY_TAB_LABEL,
                aiTabLabel = MOMO_AI_TAB_LABEL,
                dynamicTabUpperBandRatio = DYNAMIC_TAB_UPPER_BAND_RATIO,
                dynamicTabRowToleranceDp = DYNAMIC_TAB_ROW_TOLERANCE_DP,
                dynamicTabContentGapDp = DYNAMIC_TAB_CONTENT_GAP_DP,
                pageFingerprintMaxNodes = PAGE_FINGERPRINT_MAX_NODES,
                fingerprintSizeBucketPx = FINGERPRINT_SIZE_BUCKET_PX,
                minListWidthRatio = MIN_LIST_WIDTH_RATIO,
                minListHeightRatio = MIN_LIST_HEIGHT_RATIO
            )
        )
    }

    private val momoFeedGestureController by lazy(LazyThreadSafetyMode.NONE) {
        MomoFeedGestureController(
            context = this,
            pageInspector = momoPageInspector,
            onLog = ::addLog,
            config = MomoFeedGestureController.Config(
                minListWidthRatio = MIN_LIST_WIDTH_RATIO,
                minListHeightRatio = MIN_LIST_HEIGHT_RATIO
            )
        )
    }

    private val momoNavigationController by lazy(LazyThreadSafetyMode.NONE) {
        MomoNavigationController(
            context = this,
            dp = ::dp,
            performGlobalBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            config = MomoNavigationController.Config(
                maxScanNodes = PAGE_FINGERPRINT_MAX_NODES,
                tabTargetMatchToleranceDp = TAB_TARGET_MATCH_TOLERANCE_DP,
                maxClickTargetHeightRatio = MAX_TAB_CLICK_TARGET_HEIGHT_RATIO,
                maxAncestorDepth = MAX_TAB_CLICK_ANCESTOR_DEPTH
            )
        )
    }

    private val momoPopupInspector by lazy(LazyThreadSafetyMode.NONE) {
        MomoPopupInspector(
            context = this,
            maxScanNodes = PAGE_FINGERPRINT_MAX_NODES,
            findClickableAncestor = ::findClickableAncestor
        )
    }

    private val accessibilityTreeScanner by lazy(LazyThreadSafetyMode.NONE) {
        AccessibilityTreeScanner(
            service = this,
            onLog = ::addLog,
            onRefresh = ::refreshViews
        )
    }

    // The calibration controller owns its test-session state; the service retains
    // receiver registration and Android service lifecycle ownership.
    private var gestureCalibrationReceiverRegistered = false
    private val gestureCalibrationController by lazy(LazyThreadSafetyMode.NONE) {
        GestureCalibrationController(
            service = this,
            handler = mainHandler,
            isBrowseGestureBusy = {
                accountBrowseGestureInFlight || accountBrowseOverlayPassthroughActive
            },
            dp = ::dp,
            onLog = ::addLog
        )
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

    /** This receiver is registered only in Debug builds and is never exported. */
    private val debugRecoveryTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!BuildConfig.DEBUG ||
                intent?.action != ACTION_DEBUG_SIMULATE_RETURN_POSITION_FAILURE
            ) return

            if (!MonitorStore.isRunning(this@FreedomAccessibilityService) ||
                !isAccountBrowseModeEnabled() ||
                MonitorStore.targetPackage(this@FreedomAccessibilityService) != MOMO_PACKAGE
            ) {
                addLog("Debug 恢复专项测试未执行：监听未运行、养号模式未开启，或目标应用不是陌陌")
                return
            }
            if (accountBrowseTouchActive) {
                addLog("Debug 恢复专项测试未执行：当前仍检测到屏幕触摸；请停止触摸后重新触发")
                return
            }
            if (accountBrowseFeedRefreshRecoveryActive ||
                accountBrowsePhase == AccountBrowsePhase.REFRESH_DYNAMIC_FEED ||
                accountBrowsePhase == AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH
            ) {
                addLog("Debug 恢复专项测试未重复触发：当前已经处于动态刷新恢复流程")
                return
            }

            mainHandler.removeCallbacks(manualTouchResumeRunnable)
            accountBrowseManualResumeAllowed = false
            accountBrowsePausedContext = null
            accountBrowsePauseReason = ""
            addLog("Debug 专项测试指令已收到；模拟一次返回位置验证失败，转入正式主动刷新恢复流程")
            beginDynamicFeedRefreshRecovery("Debug 专项测试：模拟返回位置验证失败")
        }
    }

    private val scanRunnable = Runnable {
        scanQueued = false
        accessibilityTreeScanner.scanActiveWindow()
    }

    // Resume after the user finishes navigating or interacting with the target app.
    private val manualTouchResumeRunnable = Runnable {
        accountBrowseController.resumeAfterManualTouch(
            isMonitoringRunning = { MonitorStore.isRunning(this) },
            isBrowseModeEnabled = ::isAccountBrowseModeEnabled,
            quietPeriodMs = MANUAL_TOUCH_RESUME_MS,
            initialDynamicTabWaitTimeoutMs = INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS,
            pageStatePollMs = PAGE_STATE_POLL_MS,
            currentTimeMillis = System::currentTimeMillis,
            onLog = ::addLog,
            scheduleStep = ::scheduleAccountBrowseStep
        )
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
        if (BuildConfig.DEBUG && !debugRecoveryTestReceiverRegistered) {
            val filter = IntentFilter(ACTION_DEBUG_SIMULATE_RETURN_POSITION_FAILURE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    debugRecoveryTestReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(debugRecoveryTestReceiver, filter)
            }
            debugRecoveryTestReceiverRegistered = true
        }
        if (!gestureCalibrationReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(GestureTuning.ACTION_START_CALIBRATION)
                addAction(GestureTuning.ACTION_CANCEL_CALIBRATION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    gestureCalibrationController.receiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(gestureCalibrationController.receiver, filter)
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
                            accountBrowseController.recordManualTakeoverWhilePaused(
                                activityClass = accountBrowseCurrentActivityClass,
                                sourceFingerprint = accountBrowseSourceFingerprint,
                                detailFingerprint = accountBrowseDetailFingerprint,
                                imageBrowserActive = accountBrowseImageBrowserActive,
                                lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                                currentTimeMillis = System::currentTimeMillis
                            )
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

        val rawEventName = AccessibilityEventFormatter.rawEventName(event.eventType)
        val className = event.className?.toString().orEmpty()
        val content = event.text
            ?.joinToString(" ") { it?.toString().orEmpty() }
            ?.trim()
            .orEmpty()
            .take(300)
        val description = event.contentDescription?.toString()?.take(180).orEmpty()
        val signature = "$eventPackage|$rawEventName|$className|$content|$description"
        val now = System.currentTimeMillis()

        // Count each underlying RecyclerView update before log de-duplication.
        // Momo may emit several legitimate refresh events within the same 250 ms window.
        if (accountBrowsePhase == AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH &&
            accountBrowseFeedRefreshRecoveryActive &&
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            className.substringAfterLast('.') == "RecyclerView" &&
            now >= accountBrowseFeedRefreshActionAt
        ) {
            accountBrowseFeedRefreshRecyclerEvents++
            if (accountBrowseFeedRefreshRecyclerEvents <= 3) {
                addLog(
                    "首页双击后的动态列表内容更新事件：" +
                        "RecyclerView，第 ${accountBrowseFeedRefreshRecyclerEvents} 次"
                )
            }
        }

        // Reduce identical event bursts in the visible event log.
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

        val friendlyEvent = AccessibilityEventFormatter.friendlyEventDescription(
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
        accountBrowseScheduler.schedule(delayMs)
    }

    private fun resetAccountBrowseSession() {
        accountBrowseController.resetSession(
            beforeReset = {
                GestureTrailOverlay.clear()
                restoreMonitorOverlayTouchPassthrough()
                accountBrowseScheduler.cancel()
                mainHandler.removeCallbacks(manualTouchResumeRunnable)
            },
            afterReset = ::hideFirstSwipeCountdown
        )
    }

    private fun pauseAccountBrowse(
        reason: String,
        allowManualResume: Boolean = false
    ) {
        accountBrowseController.pauseSession(
            reason = reason,
            allowManualResume = allowManualResume,
            capturePausedContext = { phaseAtPause ->
                accountBrowsePausedContext = ManualResumeContext(
                    phaseName = phaseAtPause.name,
                    activityClass = accountBrowseCurrentActivityClass,
                    sourceFingerprint = accountBrowseSourceFingerprint,
                    detailFingerprint = accountBrowseDetailFingerprint,
                    imageBrowserActive = accountBrowseImageBrowserActive,
                    lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                    capturedAt = System.currentTimeMillis()
                )
                addLog(
                    "已保存暂停上下文：阶段=${phaseAtPause.name}；" +
                        "Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}；" +
                        "详情上下文=${if (accountBrowseDetailFingerprint.isNotBlank()) "已保存" else "无"}；" +
                        "图片浏览状态=$accountBrowseImageBrowserActive"
                )
            },
            beforePause = {
                GestureTrailOverlay.clear()
                restoreMonitorOverlayTouchPassthrough()
                accountBrowseScheduler.cancel()
            },
            hideCountdown = ::hideFirstSwipeCountdown,
            onPauseReasonChanged = { changedReason ->
                addLog("动态浏览已暂停：$changedReason")
            }
        )
    }

    private fun performAccountBrowseStep() {
        accountBrowseController.performStep()
    }

    /**
     * Inspect an exposed dialog/popup before navigation or browsing. Only an explicit,
     * non-consequential close action is automated. Permission, account, payment, deletion,
     * agreement and confirmation prompts remain for the user to handle.
     */
    private fun handleMomoPopup(root: AccessibilityNodeInfo): Boolean {
        val popup = inspectMomoPopup(root)
        val now = System.currentTimeMillis()

        if (accountBrowsePopupDismissPending) {
            if (popup == null) {
                accountBrowsePopupDismissPending = false
                accountBrowsePopupDismissDeadlineAt = 0L
                accountBrowsePopupNextAttemptAt = 0L
                accountBrowsePopupActionAttempts = 0
                accountBrowsePopupExhaustedLogged = false
                accountBrowseLastPopupSignature = ""
                addLog("弹窗关闭已验证：重新扫描后未发现原弹窗；继续当前页面导航/浏览步骤")
                return false
            }
            recyclePopupInspection(popup)
            if (now < accountBrowsePopupDismissDeadlineAt) {
                scheduleAccountBrowseStep(POPUP_VERIFY_POLL_MS)
                return true
            }
            accountBrowsePopupDismissPending = false
            accountBrowsePopupDismissDeadlineAt = 0L
            accountBrowsePopupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
            addLog("弹窗关闭动作已提交，但页面仍报告弹窗存在；等待重新分析关闭方式，不执行页面点击或滑动")
            scheduleAccountBrowseStep(POPUP_RETRY_DELAY_MS)
            return true
        }

        if (popup == null) {
            if (accountBrowseLastPopupSignature.isNotBlank()) {
                accountBrowseLastPopupSignature = ""
                accountBrowsePopupActionAttempts = 0
                accountBrowsePopupExhaustedLogged = false
                accountBrowsePopupNextAttemptAt = 0L
            }
            return false
        }

        val isNewPopup = popup.signature != accountBrowseLastPopupSignature
        if (isNewPopup) {
            accountBrowseLastPopupSignature = popup.signature
            accountBrowsePopupActionAttempts = 0
            accountBrowsePopupExhaustedLogged = false
            accountBrowsePopupNextAttemptAt = 0L
        }

        if (popup.critical) {
            if (isNewPopup) {
                addLog("识别到需要用户决定的弹窗：${popup.description}；检测到权限/账号/支付/删除/协议等重要内容，不自动确认或关闭")
            }
            recyclePopupInspection(popup)
            scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            return true
        }

        if (now < accountBrowsePopupNextAttemptAt) {
            recyclePopupInspection(popup)
            scheduleAccountBrowseStep((accountBrowsePopupNextAttemptAt - now).coerceAtLeast(POPUP_VERIFY_POLL_MS))
            return true
        }

        val hasSafeDismissMethod = popup.dismissable && popup.container != null
        val hasExplicitCloseTarget = popup.closeTarget != null
        if (!hasSafeDismissMethod && !hasExplicitCloseTarget) {
            if (!accountBrowsePopupExhaustedLogged) {
                addLog("弹窗已识别，但没有暴露可确认的安全关闭方式；保持等待用户处理，不使用屏幕外部点击或盲目返回")
                accountBrowsePopupExhaustedLogged = true
            }
            recyclePopupInspection(popup)
            scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            return true
        }

        if (accountBrowsePopupActionAttempts >= MAX_POPUP_DISMISS_ATTEMPTS) {
            if (!accountBrowsePopupExhaustedLogged) {
                addLog("已识别弹窗，但两次安全关闭尝试后仍无法确认关闭；交由用户处理，不执行动态导航或滑动")
                accountBrowsePopupExhaustedLogged = true
            }
            recyclePopupInspection(popup)
            scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            return true
        }

        var actionDescription = "没有可确认的安全关闭控件"
        var dismissed = false
        if (popup.dismissable && popup.container != null) {
            actionDescription = "AccessibilityNodeInfo.ACTION_DISMISS"
            dismissed = runCatching {
                popup.container.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
            }.getOrDefault(false)
        }
        if (!dismissed && popup.closeTarget != null) {
            actionDescription = "明确关闭控件（${popup.description}）"
            dismissed = runCatching {
                popup.closeTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }
        accountBrowsePopupActionAttempts++
        recyclePopupInspection(popup)

        if (!dismissed) {
            if (isNewPopup || accountBrowsePopupActionAttempts == 1) {
                addLog("弹窗识别：${popup.description}；未发现可执行的安全关闭方式（${actionDescription}）；不使用屏幕外部点击或盲目返回")
            }
            accountBrowsePopupNextAttemptAt = now + POPUP_RETRY_DELAY_MS
            scheduleAccountBrowseStep(POPUP_BLOCKED_RECHECK_MS)
            return true
        }

        accountBrowsePopupDismissPending = true
        accountBrowsePopupDismissDeadlineAt = now + POPUP_DISMISS_VERIFY_TIMEOUT_MS
        accountBrowseProgrammaticActionLabel = if (actionDescription.contains("ACTION_DISMISS")) {
            "Freedom ACTION_DISMISS 弹窗关闭"
        } else {
            "Freedom ACTION_CLICK $actionDescription 弹窗关闭"
        }
        accountBrowseIgnoreManualEventsUntil = now + POPUP_DISMISS_VERIFY_TIMEOUT_MS
        addLog("识别到可安全关闭的陌陌弹窗：${popup.description}；采用 $actionDescription；等待重新扫描验证关闭结果")
        scheduleAccountBrowseStep(POPUP_VERIFY_POLL_MS)
        return true
    }

    private fun inspectMomoPopup(root: AccessibilityNodeInfo): PopupInspection? =
        momoPopupInspector.inspect(root)

    private fun recyclePopupInspection(popup: PopupInspection) =
        momoPopupInspector.recycle(popup)

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
                    val externalPopup = inspectMomoPopup(root)
                    if (externalPopup != null) {
                        reason = "当前前台为外部系统窗口/弹窗：${externalPopup.description}；需要用户处理，不对其他应用执行关闭或点击"
                        recyclePopupInspection(externalPopup)
                    } else {
                        reason = "陌陌当前不在前台；保留导航上下文，不对其他应用执行操作"
                    }
                } else if (handleMomoPopup(root)) {
                    return
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
                                if (accountBrowseFeedRefreshRecoveryActive) {
                                    accountBrowsePhase = AccountBrowsePhase.REFRESH_DYNAMIC_FEED
                                    accountBrowseFeedRefreshWaitReason = ""
                                    addLog(
                                        "已重新确认首页顶部‘动态’标签及其下方列表；" +
                                            "进入首页连续双击刷新验证，不使用旧列表位置继续滑动"
                                    )
                                    scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                                    return
                                }
                                accountBrowseImageBrowserActive = false
                                accountBrowseImageBrowserReturnActivityClass = ""
                                accountBrowseDetailActivityClass = ""
                                accountBrowseDetailFingerprint = ""
                                accountBrowseProgrammaticActionLabel = ""
                                accountBrowseDetailDwellDueAt = 0L
                                accountBrowseDetailDwellStartedAt = 0L
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

    /**
     * Only issue Back for page types whose relationship to the current dynamic task is known.
     * An unfamiliar Momo Activity may be a chat, profile, permission flow, or another main page;
     * treating every non-MainTab Activity as a child page can accidentally leave the intended task.
     */
    private fun isKnownMomoChildPage(): Boolean {
        val activity = accountBrowseCurrentActivityClass
        return when (activity) {
            MOMO_FEED_IMAGE_BROWSER_ACTIVITY -> accountBrowseImageBrowserActive ||
                accountBrowsePausedContext?.imageBrowserActive == true
            MOMO_DYNAMIC_DETAIL_ACTIVITY ->
                accountBrowseDetailActivityClass == MOMO_DYNAMIC_DETAIL_ACTIVITY ||
                    accountBrowseDetailFingerprint.isNotBlank() ||
                    accountBrowsePausedContext?.detailFingerprint?.isNotBlank() == true ||
                    (accountBrowsePausedContext?.phaseName?.let { phaseName ->
                        phaseName in setOf(
                            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD.name,
                            AccountBrowsePhase.DETAIL_DWELL.name,
                            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN.name,
                            AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE.name
                        )
                    } == true) || accountBrowseProgrammaticActionLabel.contains("动态卡片")
            else -> false
        }
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
        val clicked = momoNavigationController.performClickAndRecycle(target)
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
        val accepted = runCatching { momoNavigationController.performBack() }.getOrDefault(false)
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
        if (handleMomoPopup(root)) {
            runCatching { root.recycle() }
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

    private fun inspectHomeBottomNav(root: AccessibilityNodeInfo): HomeBottomNavEvidence? =
        momoPageInspector.inspectHomeBottomNav(root)

    private fun findClickableNodeForLabel(
        root: AccessibilityNodeInfo,
        label: String,
        expectedBounds: Rect,
        minCenterY: Int,
        maxCenterY: Int
    ): AccessibilityNodeInfo? = momoNavigationController.findClickableNodeForLabel(
        root = root,
        label = label,
        expectedBounds = expectedBounds,
        minCenterY = minCenterY,
        maxCenterY = maxCenterY
    )

    private fun findClickableAncestor(
        node: AccessibilityNodeInfo,
        labelBounds: Rect
    ): AccessibilityNodeInfo? = momoNavigationController.findClickableAncestor(node, labelBounds)

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
        if (handleMomoPopup(root)) {
            runCatching { root.recycle() }
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

    /**
     * A mismatched return snapshot is not treated as a permanent stop. Re-identify
     * Home -> Dynamic, then use Momo's own Home-tab reselect refresh and verify it.
     */
    private fun beginDynamicFeedRefreshRecovery(reason: String) {
        accountBrowseScheduler.cancel()
        GestureTrailOverlay.clear()
        restoreMonitorOverlayTouchPassthrough("动态页面刷新恢复准备")
        accountBrowseFeedRefreshRecoveryActive = true
        accountBrowseFeedRefreshAttempts = 0
        accountBrowseFeedRefreshBeforeSnapshot = ""
        accountBrowseFeedRefreshLastSnapshot = ""
        accountBrowseFeedRefreshStableSamples = 0
        accountBrowseFeedRefreshRecyclerEvents = 0
        accountBrowseFeedRefreshActionAt = 0L
        accountBrowseFeedRefreshDeadlineAt = 0L
        accountBrowseFeedRefreshWaitReason = ""
        accountBrowseNavigationActionKey = ""
        accountBrowseNavigationBeforeActivity = ""
        accountBrowseNavigationBeforeFingerprint = ""
        accountBrowseNavigationActionDeadlineAt = 0L
        accountBrowseNavigationActionAttempts = 0
        accountBrowseRecoveryBackAttempts = 0
        accountBrowseRecoveryHomeClickAttempts = 0
        accountBrowseRecoveryDynamicClickAttempts = 0
        accountBrowseDynamicTabClickConfirmed = false
        accountBrowseLastDynamicTabWaitReason = ""
        accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        addLog(
            "$reason；不再尝试猜测旧列表位置，开始主动恢复：" +
                "识别陌陌底部‘首页’，确认顶部‘动态’标签，再执行首页连续双击刷新"
        )
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /** Submit two actual tap strokes at the verified bottom Home tab. */
    private fun refreshDynamicFeed() {
        if (!accountBrowseFeedRefreshRecoveryActive) {
            pauseAccountBrowse("动态刷新恢复状态丢失；没有执行滑动")
            return
        }
        if (accountBrowseFeedRefreshAttempts >= MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS) {
            accountBrowseFeedRefreshRecoveryActive = false
            pauseAccountBrowse("首页连续双击刷新已达到最大尝试次数；没有确认动态列表重新加载，因此未继续滑动")
            return
        }

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: run {
            scheduleDynamicFeedRefreshNavigationWait("暂时无法读取陌陌页面")
            return
        }
        try {
            val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
            if (activePackage != MOMO_PACKAGE) {
                scheduleDynamicFeedRefreshNavigationWait("当前前台不是陌陌")
                return
            }
            if (handleMomoPopup(root)) return

            val tabEvidence = inspectDynamicTabRow(root).evidence
            val dynamicTabConfirmed = tabEvidence != null && (
                (tabEvidence.selectionKnown && tabEvidence.dynamicSelected) ||
                    (!tabEvidence.selectionKnown && accountBrowseDynamicTabClickConfirmed)
                )
            val readiness = inspectMomoFeedReadiness(root)
            if (readiness.fingerprint == null || !dynamicTabConfirmed) {
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return
            }

            val homeEvidence = inspectHomeBottomNav(root)
            if (homeEvidence == null) {
                scheduleDynamicFeedRefreshNavigationWait("没有识别到底部‘首页’导航控件")
                return
            }

            if (homeEvidence.selectionKnown && !homeEvidence.homeSelected) {
                val homeTarget = findClickableNodeForLabel(
                    root = root,
                    label = MOMO_HOME_TAB_LABEL,
                    expectedBounds = homeEvidence.homeBounds,
                    minCenterY = (resources.displayMetrics.heightPixels * HOME_NAV_LOWER_BAND_RATIO).toInt(),
                    maxCenterY = resources.displayMetrics.heightPixels
                )
                if (homeTarget == null) {
                    scheduleDynamicFeedRefreshNavigationWait("底部‘首页’未选中，但没有找到可安全点击的首页控件")
                    return
                }
                addLog("已确认底部当前不是‘首页’；先单击底部‘首页’并验证导航，再执行连续双击刷新")
                submitNavigationClick(
                    root = root,
                    target = homeTarget,
                    actionKey = NAV_ACTION_HOME_TAB,
                    actionDescription = "陌陌底部‘首页’标签"
                )
                return
            }

            val homeTarget = findClickableNodeForLabel(
                root = root,
                label = MOMO_HOME_TAB_LABEL,
                expectedBounds = homeEvidence.homeBounds,
                minCenterY = (resources.displayMetrics.heightPixels * HOME_NAV_LOWER_BAND_RATIO).toInt(),
                maxCenterY = resources.displayMetrics.heightPixels
            )
            if (homeTarget == null) {
                scheduleDynamicFeedRefreshNavigationWait("已识别底部‘首页’，但没有找到可点击的首页控件")
                return
            }
            val targetBounds = Rect()
            runCatching { homeTarget.getBoundsInScreen(targetBounds) }
            runCatching { homeTarget.recycle() }
            val tapX = homeEvidence.homeBounds.centerX()
            val tapY = homeEvidence.homeBounds.centerY()
            if (targetBounds.isEmpty || !targetBounds.contains(tapX, tapY)) {
                scheduleDynamicFeedRefreshNavigationWait("首页文字位置不在可点击控件范围内；拒绝盲目点击")
                return
            }

            val listNode = findScrollableFeedContainer(root)
            if (listNode == null) {
                scheduleDynamicFeedRefreshNavigationWait("动态列表容器尚未出现")
                return
            }
            val beforeSnapshot = try {
                captureVisibleFeedNodeSnapshot(listNode)
            } finally {
                runCatching { listNode.recycle() }
            }
            if (beforeSnapshot.isBlank()) {
                scheduleDynamicFeedRefreshNavigationWait("当前动态列表没有可读取的可见内容；暂不执行刷新点击")
                return
            }

            if (!enableMonitorOverlayTouchPassthrough("首页连续双击刷新")) {
                accountBrowseFeedRefreshRecoveryActive = false
                pauseAccountBrowse("实时监控悬浮窗无法启用触摸穿透；首页双击刷新未执行")
                return
            }

            val firstTapPath = Path().apply {
                moveTo(tapX.toFloat(), tapY.toFloat())
                lineTo(tapX + 1f, tapY + 1f)
            }
            val secondTapPath = Path().apply {
                moveTo(tapX.toFloat(), tapY.toFloat())
                lineTo(tapX + 1f, tapY + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        firstTapPath,
                        0L,
                        HOME_REFRESH_TAP_DURATION_MS
                    )
                )
                .addStroke(
                    GestureDescription.StrokeDescription(
                        secondTapPath,
                        HOME_REFRESH_TAP_INTERVAL_MS,
                        HOME_REFRESH_TAP_DURATION_MS
                    )
                )
                .build()

            accountBrowseFeedRefreshAttempts++
            accountBrowseFeedRefreshBeforeSnapshot = beforeSnapshot
            accountBrowseFeedRefreshLastSnapshot = ""
            accountBrowseFeedRefreshStableSamples = 0
            accountBrowseFeedRefreshRecyclerEvents = 0
            accountBrowseFeedRefreshActionAt = System.currentTimeMillis()
            accountBrowseFeedRefreshDeadlineAt = accountBrowseFeedRefreshActionAt + DYNAMIC_FEED_REFRESH_VERIFY_TIMEOUT_MS
            accountBrowseFeedRefreshWaitReason = ""
            accountBrowsePhase = AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH
            accountBrowseProgrammaticActionLabel = "Freedom dispatchGesture ACTION_CLICK 连续双击首页刷新动态"
            accountBrowseIgnoreManualEventsUntil = accountBrowseFeedRefreshActionAt + HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS
            accountBrowseGestureInFlight = true
            accountBrowseTouchStartedDuringGesture = false
            accountBrowseTouchEndedDuringGesture = false
            addLog(
                "已确认陌陌首页内顶部‘动态’页面；底部导航证据=${homeEvidence.diagnostic}；" +
                    "对底部‘首页’执行第 $accountBrowseFeedRefreshAttempts/$MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS 次连续双击，" +
                    "点击位置=($tapX,$tapY)，两次点击间隔=${HOME_REFRESH_TAP_INTERVAL_MS - HOME_REFRESH_TAP_DURATION_MS} 毫秒；" +
                    "开始等待动态列表重新加载证据"
            )

            val accepted = runCatching {
                dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        accountBrowseGestureInFlight = false
                        accountBrowseTouchStartedDuringGesture = false
                        accountBrowseTouchEndedDuringGesture = false
                        restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势完成")
                        if (accountBrowsePhase != AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH ||
                            !MonitorStore.isRunning(this@FreedomAccessibilityService)
                        ) return
                        addLog("Android 已完成首页连续双击手势；正在检查动态列表是否确实重新加载")
                        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        val touchStarted = accountBrowseTouchStartedDuringGesture
                        val touchEnded = accountBrowseTouchEndedDuringGesture
                        accountBrowseGestureInFlight = false
                        accountBrowseTouchStartedDuringGesture = false
                        accountBrowseTouchEndedDuringGesture = false
                        restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势取消")
                        if (accountBrowsePhase != AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH) return
                        val interruptedByTouch = touchStarted || touchEnded
                        if (interruptedByTouch) {
                            pauseAccountBrowse("首页连续双击刷新被屏幕触摸中断；等待用户操作结束后重新识别页面", allowManualResume = true)
                            if (touchEnded) {
                                accountBrowseTouchActive = false
                                mainHandler.removeCallbacks(manualTouchResumeRunnable)
                                mainHandler.postDelayed(manualTouchResumeRunnable, MANUAL_TOUCH_RESUME_MS)
                            } else {
                                accountBrowseTouchActive = true
                            }
                        } else {
                            accountBrowseFeedRefreshRecoveryActive = false
                            pauseAccountBrowse("Android 取消了首页连续双击刷新手势；未确认动态列表已重新加载")
                        }
                    }
                }, mainHandler)
            }.getOrDefault(false)

            if (!accepted) {
                accountBrowseGestureInFlight = false
                restoreMonitorOverlayTouchPassthrough("首页连续双击刷新手势提交失败")
                accountBrowseFeedRefreshRecoveryActive = false
                pauseAccountBrowse("Android 未接受首页连续双击刷新手势；没有执行后续滑动")
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /** Verify a real RecyclerView reload signal and a stable, recognizable Dynamic feed. */
    private fun verifyDynamicFeedRefresh() {
        if (!accountBrowseFeedRefreshRecoveryActive) {
            pauseAccountBrowse("动态刷新验证状态丢失；没有执行滑动")
            return
        }
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: run {
            scheduleOrRetryDynamicFeedRefresh("暂时无法读取刷新后的页面")
            return
        }

        var currentSnapshot = ""
        var isDynamicFeedReady = false
        var readinessReason = "尚未识别到完整动态列表"
        try {
            val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
            if (activePackage != MOMO_PACKAGE) {
                scheduleOrRetryDynamicFeedRefresh("刷新期间陌陌不在前台")
                return
            }
            if (handleMomoPopup(root)) return

            val readiness = inspectMomoFeedReadiness(root)
            readinessReason = readiness.reason
            val homeEvidence = inspectHomeBottomNav(root)
            val tabEvidence = inspectDynamicTabRow(root).evidence
            val dynamicTabConfirmed = tabEvidence != null && (
                (tabEvidence.selectionKnown && tabEvidence.dynamicSelected) ||
                    (!tabEvidence.selectionKnown && accountBrowseDynamicTabClickConfirmed)
                )
            val homeConfirmed = homeEvidence != null && (
                (homeEvidence.selectionKnown && homeEvidence.homeSelected) ||
                    (!homeEvidence.selectionKnown && dynamicTabConfirmed)
                )
            if (readiness.fingerprint != null && dynamicTabConfirmed && homeConfirmed) {
                val listNode = findScrollableFeedContainer(root)
                if (listNode != null) {
                    try {
                        currentSnapshot = captureVisibleFeedNodeSnapshot(listNode)
                        isDynamicFeedReady = currentSnapshot.isNotBlank()
                    } finally {
                        runCatching { listNode.recycle() }
                    }
                }
            } else if (readiness.fingerprint != null && !dynamicTabConfirmed) {
                readinessReason = "顶部‘动态’标签选中状态未能确认"
            } else if (readiness.fingerprint != null && !homeConfirmed) {
                readinessReason = "顶部动态列表存在，但底部首页选中状态未能确认"
            }
        } finally {
            runCatching { root.recycle() }
        }

        if (!isDynamicFeedReady) {
            accountBrowseFeedRefreshStableSamples = 0
            accountBrowseFeedRefreshLastSnapshot = ""
            if (readinessReason != accountBrowseFeedRefreshWaitReason) {
                accountBrowseFeedRefreshWaitReason = readinessReason
                addLog("首页双击刷新验证等待：$readinessReason")
            }
        } else {
            accountBrowseFeedRefreshWaitReason = ""
            accountBrowseFeedRefreshStableSamples = if (currentSnapshot == accountBrowseFeedRefreshLastSnapshot) {
                accountBrowseFeedRefreshStableSamples + 1
            } else {
                1
            }
            accountBrowseFeedRefreshLastSnapshot = currentSnapshot
        }

        val snapshotChanged = accountBrowseFeedRefreshBeforeSnapshot.isNotBlank() &&
            currentSnapshot.isNotBlank() &&
            currentSnapshot != accountBrowseFeedRefreshBeforeSnapshot
        val reloadEvidence = FeedRefreshVerificationPolicy.hasReloadEvidence(
            snapshotChanged = snapshotChanged,
            recyclerContentChangeEvents = accountBrowseFeedRefreshRecyclerEvents
        )
        if (isDynamicFeedReady) {
            addLog(
                "刷新验证采样：动态页面=${if (isDynamicFeedReady) "已确认" else "未确认"}；" +
                    "列表内容变化=${if (snapshotChanged) "是" else "否"}；" +
                    "RecyclerView 内容更新事件=${accountBrowseFeedRefreshRecyclerEvents}；" +
                    "列表稳定=${accountBrowseFeedRefreshStableSamples}/$REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES；" +
                    "刷新证据=${if (reloadEvidence) "已取得" else "不足"}"
            )
        }

        if (FeedRefreshVerificationPolicy.canResumeBrowsing(
                isDynamicFeedPage = isDynamicFeedReady,
                snapshotChanged = snapshotChanged,
                recyclerContentChangeEvents = accountBrowseFeedRefreshRecyclerEvents,
                stableSamples = accountBrowseFeedRefreshStableSamples,
                requiredStableSamples = REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES
            )
        ) {
            completeDynamicFeedRefreshRecovery(snapshotChanged)
            return
        }

        scheduleOrRetryDynamicFeedRefresh(
            if (reloadEvidence) {
                "已观察到刷新迹象，等待列表连续稳定"
            } else {
                "尚未取得足够的动态列表重新加载证据"
            }
        )
    }

    private fun scheduleDynamicFeedRefreshNavigationWait(reason: String) {
        if (reason != accountBrowseFeedRefreshWaitReason) {
            accountBrowseFeedRefreshWaitReason = reason
            addLog("首页双击刷新准备等待：$reason")
        }
        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            accountBrowseFeedRefreshRecoveryActive = false
            pauseAccountBrowse("无法确认陌陌底部‘首页’和顶部‘动态’导航控件；主动刷新恢复超时")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun scheduleOrRetryDynamicFeedRefresh(reason: String) {
        if (System.currentTimeMillis() < accountBrowseFeedRefreshDeadlineAt) {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }
        if (accountBrowseFeedRefreshAttempts < MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS) {
            addLog(
                "$reason；第 $accountBrowseFeedRefreshAttempts 次首页连续双击未能完成验证；" +
                    "重新识别首页/动态标签后再进行最后一次刷新尝试"
            )
            accountBrowseFeedRefreshLastSnapshot = ""
            accountBrowseFeedRefreshStableSamples = 0
            accountBrowseFeedRefreshRecyclerEvents = 0
            accountBrowseFeedRefreshWaitReason = ""
            accountBrowseRecoveryBackAttempts = 0
            accountBrowseRecoveryHomeClickAttempts = 0
            accountBrowseRecoveryDynamicClickAttempts = 0
            accountBrowseDynamicTabClickConfirmed = false
            accountBrowseLastDynamicTabWaitReason = ""
            accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        accountBrowseFeedRefreshRecoveryActive = false
        pauseAccountBrowse(
            "已尝试 $MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS 次首页连续双击，但仍无法确认动态列表重新加载并稳定；" +
                "为避免在未知页面滑动，已安全暂停"
        )
    }

    private fun completeDynamicFeedRefreshRecovery(snapshotChanged: Boolean) {
        val attempts = accountBrowseFeedRefreshAttempts
        val events = accountBrowseFeedRefreshRecyclerEvents
        accountBrowseFeedRefreshRecoveryActive = false
        accountBrowseDetailActivityClass = ""
        accountBrowseDetailFingerprint = ""
        accountBrowseProgrammaticActionLabel = ""
        accountBrowseDetailDwellDueAt = 0L
        accountBrowseDetailDwellStartedAt = 0L
        accountBrowseDetailDwellRemainingMs = 0L
        accountBrowseImageBrowserActive = false
        accountBrowseImageBrowserReturnActivityClass = ""
        accountBrowseSourceSnapshot = ""
        accountBrowseLastReturnedSnapshot = ""
        accountBrowseReturnedStableSamples = 0
        accountBrowseReturnMismatchLogged = false
        accountBrowseSwipeCount = 0
        accountBrowseLastStableFingerprint = ""
        accountBrowseStableSamples = 0
        accountBrowseDeadlineAt = System.currentTimeMillis() + INITIAL_FEED_STABILITY_TIMEOUT_MS
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE
        addLog(
            "首页连续双击刷新已验证成功（尝试次数=$attempts；" +
                "列表内容变化=${if (snapshotChanged) "是" else "否"}；RecyclerView 内容更新事件=$events；" +
                "列表连续稳定 $REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES 次）；" +
                "不再沿用旧位置，已建立刷新后的新动态列表基准，准备恢复浏览"
        )
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
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

    private fun obtainTargetRootOrPause(pauseWhenUnavailable: Boolean = true): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            if (pauseWhenUnavailable) {
                pauseAccountBrowse("暂时无法读取当前页面；请确认陌陌仍在前台")
            }
            return null
        }
        val activePackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (activePackage != MOMO_PACKAGE) {
            val externalPopup = inspectMomoPopup(root)
            if (externalPopup != null) {
                addLog("检测到陌陌以外的系统窗口/弹窗：${externalPopup.description}；不对系统或其他应用执行点击，等待用户处理后重新定位动态")
                recyclePopupInspection(externalPopup)
                runCatching { root.recycle() }
                beginDynamicNavigationRecovery("检测到陌陌外部系统窗口，需要用户先处理")
                return null
            }
            runCatching { root.recycle() }
            pauseAccountBrowse("当前没有打开陌陌；自动浏览已暂停")
            return null
        }
        if (handleMomoPopup(root)) {
            runCatching { root.recycle() }
            return null
        }
        return root
    }

    /** Temporarily remove the live monitor window from the touch path for the swipe. */
    private fun enableMonitorOverlayTouchPassthrough(
        actionDescription: String = "首次滑动"
    ): Boolean {
        if (!MonitorOverlay.isAttached()) {
            accountBrowseOverlayPassthroughActive = false
            addLog("实时监控悬浮窗当前未附加（配置中‘悬浮日志’已关闭）；$actionDescription 不经过悬浮窗")
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
            "$actionDescription 前已临时隐藏实时监控悬浮窗并启用触摸穿透。" +
                "$before；切换后=${MonitorOverlay.touchStateDescription()}"
        )
        return true
    }

    private fun restoreMonitorOverlayTouchPassthrough(
        actionDescription: String = "首次滑动检查结束"
    ): Boolean {
        if (!accountBrowseOverlayPassthroughActive) return true
        val restored = MonitorOverlay.setTouchPassthrough(false)
        if (restored) {
            accountBrowseOverlayPassthroughActive = false
            mainHandler.removeCallbacks(monitorOverlayPassthroughWatchdog)
            addLog("$actionDescription：实时监控悬浮窗已恢复显示和触摸操作")
            return true
        }
        addLog("警告：实时监控悬浮窗触摸状态恢复失败；悬浮窗可能仍处于隐藏/穿透状态")
        return false
    }

    /** Resolve the same visible feed container used by the readiness gate and return
     * its screen-space bounds, clipped below the Dynamic tab row and to the display. */
    private fun resolveFeedGestureTarget(root: AccessibilityNodeInfo): FeedGestureTarget? =
        momoFeedGestureController.resolveFeedGestureTarget(root)

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
    private fun captureVisibleFeedNodeSnapshot(root: AccessibilityNodeInfo): String =
        momoFeedGestureController.captureVisibleFeedNodeSnapshot(root)

    private fun loadScrollGestureProfile(): ScrollGestureProfile =
        momoFeedGestureController.loadScrollGestureProfile()

    /** Create fresh in-bounds endpoints and a cosine-timed cubic Bezier scroll path. */
    private fun createSmoothScrollPath(
        bounds: Rect,
        profile: ScrollGestureProfile
    ): ScrollGesturePlan? = momoFeedGestureController.createSmoothScrollPath(bounds, profile)

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
            accountBrowseCurrentActivityClass == MOMO_DYNAMIC_DETAIL_ACTIVITY &&
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
                accountBrowseDetailActivityClass = accountBrowseCurrentActivityClass
                    .ifBlank { MOMO_DYNAMIC_DETAIL_ACTIVITY }
                val dwell = randomBrowseDelayMs()
                accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
                accountBrowseDetailDwellRemainingMs = dwell
                accountBrowseDetailDwellStartedAt = System.currentTimeMillis()
                accountBrowseDetailDwellDueAt = accountBrowseDetailDwellStartedAt + dwell
                addLog("动态详情已确认载入；本次阅读停留 ${dwell / 1000.0} 秒")
                scheduleAccountBrowseStep(dwell)
                return
            }
        } else {
            accountBrowseLastStableFingerprint = ""
            accountBrowseStableSamples = 0
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            beginDynamicNavigationRecovery("等待动态详情超时；当前 Activity=${accountBrowseCurrentActivityClass.ifBlank { "未知" }}，重新分析当前页面层级")
        } else {
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
        }
    }

    private fun returnFromDetailPage() {
        val root = obtainTargetRootOrPause() ?: return
        val identity = try {
            inspectCurrentDynamicDetailIdentity(root)
        } finally {
            runCatching { root.recycle() }
        }

        if (identity.isFeedPage) {
            addLog("详情停留结束时重新扫描发现已经回到动态列表；不再重复执行返回，直接验证原列表位置")
            accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
            accountBrowseReturnedStableSamples = 0
            accountBrowseLastReturnedSnapshot = ""
            accountBrowseReturnMismatchLogged = false
            accountBrowseDeadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
            scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            return
        }

        if (!identity.identityConfirmed) {
            addLog("详情停留结束时无法确认当前页面仍是原动态详情：${identity.reason}；交由主动导航重新分析页面层级，不执行猜测性返回")
            beginDynamicNavigationRecovery("详情页面身份验证未通过：${identity.reason}")
            return
        }

        if (identity.fingerprint.signature != accountBrowseDetailFingerprint) {
            addLog(
                "动态详情内容结构在阅读期间发生变化（可见节点=${identity.fingerprint.nodeCount}）；" +
                    "但 Activity 和详情页面身份仍然匹配，按正常内容更新处理，不触发暂停"
            )
            accountBrowseDetailFingerprint = identity.fingerprint.signature
        }

        accountBrowseProgrammaticActionLabel = "Freedom GLOBAL_ACTION_BACK 返回详情"
        accountBrowseIgnoreManualEventsUntil = System.currentTimeMillis() + 1_500L
        if (!momoNavigationController.performBack()) {
            beginDynamicNavigationRecovery("详情页面身份已确认，但系统未接受返回操作")
            return
        }
        accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
        accountBrowseReturnedStableSamples = 0
        accountBrowseLastReturnedSnapshot = ""
        accountBrowseReturnMismatchLogged = false
        accountBrowseDeadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
        addLog("动态详情身份已确认；阅读停留结束，正在返回并验证原动态列表位置")
        scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
    }

    /**
     * The inspector handles the read-only page check; current Activity values remain owned by
     * the service's active navigation session.
     */
    private fun inspectCurrentDynamicDetailIdentity(
        root: AccessibilityNodeInfo
    ): DetailPageIdentityInspection = momoPageInspector.inspectCurrentDynamicDetailIdentity(
        root = root,
        expectedActivity = accountBrowseDetailActivityClass.ifBlank { MOMO_DYNAMIC_DETAIL_ACTIVITY },
        currentActivity = accountBrowseCurrentActivityClass
    )

    /** Machine-detected page uncertainty should enter the same verified navigator, not dead-end PAUSED. */
    private fun beginDynamicNavigationRecovery(reason: String) {
        accountBrowseController.beginNavigationRecovery(
            reason = reason,
            beforeRecovery = {
                GestureTrailOverlay.clear()
                restoreMonitorOverlayTouchPassthrough()
                accountBrowseScheduler.cancel()
            },
            initialDynamicTabWaitTimeoutMs = INITIAL_DYNAMIC_TAB_WAIT_TIMEOUT_MS,
            pageStatePollMs = PAGE_STATE_POLL_MS,
            currentTimeMillis = System::currentTimeMillis,
            onStarted = { recoveryReason ->
                addLog("页面状态需要重新定位：$recoveryReason；启动主动导航，重新识别页面层级并验证动态标签")
            },
            scheduleStep = ::scheduleAccountBrowseStep
        )
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
                accountBrowseScheduler.cancel()
                accountBrowseManualResumeAllowed = false
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
        val identity = try {
            inspectCurrentDynamicDetailIdentity(root)
        } finally {
            runCatching { root.recycle() }
        }

        val expectedActivity = accountBrowseImageBrowserReturnActivityClass
            .ifBlank { accountBrowseDetailActivityClass.ifBlank { MOMO_DYNAMIC_DETAIL_ACTIVITY } }
        if (accountBrowseCurrentActivityClass != expectedActivity || !identity.identityConfirmed) {
            if (identity.isFeedPage) {
                addLog("退出图片浏览器后已经回到动态列表；直接验证列表状态，不重复返回")
                accountBrowseImageBrowserActive = false
                accountBrowsePhase = AccountBrowsePhase.WAIT_FOR_LIST_RETURN
                accountBrowseDeadlineAt = System.currentTimeMillis() + LIST_RETURN_TIMEOUT_MS
                accountBrowseReturnedStableSamples = 0
                accountBrowseLastReturnedSnapshot = ""
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
                return
            }
            beginDynamicNavigationRecovery("图片浏览器返回后未能确认原动态详情身份：${identity.reason}")
            return
        }

        // The content fingerprint may change while images and text finish loading. Two
        // consecutive identity checks on the expected detail Activity are sufficient;
        // descendant equality is intentionally not required.
        accountBrowseStableSamples++
        accountBrowseDetailFingerprint = identity.fingerprint.signature
        accountBrowseDetailActivityClass = expectedActivity
        if (accountBrowseStableSamples >= REQUIRED_STABLE_PAGE_SAMPLES) {
            accountBrowseImageBrowserActive = false
            accountBrowseImageBrowserReturnActivityClass = ""
            accountBrowseDetailDwellStartedAt = System.currentTimeMillis()
            accountBrowseDetailDwellDueAt = accountBrowseDetailDwellStartedAt + accountBrowseDetailDwellRemainingMs
            accountBrowsePhase = AccountBrowsePhase.DETAIL_DWELL
            val remaining = accountBrowseDetailDwellRemainingMs
            addLog(
                "图片浏览器已返回原动态详情；页面身份连续 ${REQUIRED_STABLE_PAGE_SAMPLES} 次确认，" +
                    "内容节点变化不影响页面身份；继续剩余阅读时间 ${remaining / 1000.0} 秒"
            )
            scheduleAccountBrowseStep(remaining.coerceAtLeast(300L))
            return
        }

        if (System.currentTimeMillis() >= accountBrowseDeadlineAt) {
            beginDynamicNavigationRecovery("图片浏览器已退出，但原动态详情身份未能连续确认")
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
                (accountBrowseProgrammaticActionLabel.contains("ACTION_CLICK") ||
                    accountBrowseProgrammaticActionLabel.contains("弹窗关闭")) -> true
            else -> false
        }
    }

    private fun isDynamicTabStartupScroll(eventType: Int, className: String, now: Long): Boolean {
        val hasTouchEvidence = accountBrowseTouchActive ||
            now - accountBrowseLastGlobalTouchEventAt in 0..GLOBAL_TOUCH_EVENT_ASSOCIATION_MS
        return DynamicTabStartupScrollPolicy.shouldIgnore(
            isScrollEvent = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
            isDynamicTabWait = accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB,
            isMainActivity = accountBrowseCurrentActivityClass == MOMO_MAIN_ACTIVITY,
            isRecyclerView = className.substringAfterLast('.') == "RecyclerView",
            elapsedSinceWaitStartedMs = now - accountBrowseSession.dynamicTabWaitStartedAt,
            hasTouchEvidence = hasTouchEvidence
        )
    }

    private fun isDetailLoadContainerScroll(eventType: Int, className: String): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD &&
            accountBrowseCurrentActivityClass == MOMO_DYNAMIC_DETAIL_ACTIVITY &&
            className.substringAfterLast('.') == "RecyclerView"

    private fun isDetailDwellStartupScroll(eventType: Int, className: String, now: Long): Boolean =
        DetailDwellScrollPolicy.shouldIgnore(
            isScrollEvent = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED,
            isDetailActivity = accountBrowseCurrentActivityClass == MOMO_DYNAMIC_DETAIL_ACTIVITY,
            isDetailDwell = accountBrowsePhase == AccountBrowsePhase.DETAIL_DWELL,
            isRecyclerView = className.substringAfterLast('.') == "RecyclerView",
            elapsedSinceDwellStartMs = now - accountBrowseDetailDwellStartedAt
        )

    private fun isNavigationVerificationScroll(eventType: Int, className: String): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.VERIFY_NAVIGATION_ACTION &&
            className.substringAfterLast('.') == "RecyclerView"

    private fun isDynamicFeedRefreshStartupScroll(
        eventType: Int,
        className: String,
        now: Long
    ): Boolean =
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            accountBrowsePhase == AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH &&
            className.substringAfterLast('.') == "RecyclerView" &&
            now - accountBrowseFeedRefreshActionAt in 0..HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS

    private fun classifyInteractionEventOrigin(eventType: Int, now: Long, className: String): String = when {
        isDynamicTabStartupScroll(eventType, className, now) ->
            "陌陌首页刚进入动态标签识别阶段；2 秒内 RecyclerView 初始化滚动事件且没有触摸证据，不按用户操作处理"
        isDetailLoadContainerScroll(eventType, className) ->
            "动态详情加载期间 RecyclerView 的容器滚动/布局事件；缺少独立触摸证据，不按用户操作处理"
        isDetailDwellStartupScroll(eventType, className, now) ->
            "动态详情刚确认载入；2 秒缓冲期内 RecyclerView 滚动/布局事件不单独认定为用户操作"
        isNavigationVerificationScroll(eventType, className) ->
            "导航动作后的页面切换/列表加载事件；正在验证页面层级，单独的 RecyclerView 滚动不足以认定为用户操作"
        isDynamicFeedRefreshStartupScroll(eventType, className, now) ->
            "首页连续双击刷新后的 RecyclerView 加载/回顶事件；处于刷新动作验证窗口，不按用户操作处理"
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
        if (isDynamicTabStartupScroll(eventType, className, now)) {
            addLog("陌陌首页刚进入动态标签识别阶段；2 秒缓冲期内检测到 RecyclerView 初始化滚动事件，没有独立触摸证据，不触发暂停")
            return
        }
        if (isDetailLoadContainerScroll(eventType, className)) {
            addLog("详情页面正在初始化；检测到 RecyclerView 滚动/布局事件，但没有独立用户触摸证据，来源标记为待确认，不触发暂停")
            return
        }
        if (isDetailDwellStartupScroll(eventType, className, now)) {
            addLog("动态详情刚确认载入；2 秒缓冲期内检测到 RecyclerView 滚动/布局事件，没有独立触摸证据，不触发暂停")
            return
        }
        if (isNavigationVerificationScroll(eventType, className)) {
            addLog("导航页面正在切换或加载；检测到 RecyclerView 滚动事件，但没有独立用户触摸证据，暂不触发暂停")
            return
        }
        if (isDynamicFeedRefreshStartupScroll(eventType, className, now)) {
            addLog("首页连续双击后检测到 RecyclerView 滚动/回顶事件；按刷新动作反馈处理，不触发暂停")
            return
        }
        if (accountBrowseImageBrowserActive ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN ||
            accountBrowsePhase == AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE
        ) return

        val eventName = AccessibilityEventFormatter.rawEventName(eventType)
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
            accountBrowseController.recordManualTakeoverWhilePaused(
                activityClass = accountBrowseCurrentActivityClass,
                sourceFingerprint = accountBrowseSourceFingerprint,
                detailFingerprint = accountBrowseDetailFingerprint,
                imageBrowserActive = accountBrowseImageBrowserActive,
                lastProgrammaticAction = accountBrowseProgrammaticActionLabel,
                currentTimeMillis = { now }
            )
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
        // A temporarily unavailable accessibility root is not proof that the user left
        // the feed. Keep the existing return-verification deadline and poll again.
        val root = obtainTargetRootOrPause(pauseWhenUnavailable = false)
        if (root == null) {
            accountBrowseReturnedStableSamples = 0
            accountBrowseLastReturnedSnapshot = ""
            val decision = FeedReturnVerificationPolicy.decide(
                rootAvailable = false,
                isFeedPage = false,
                positionMatches = false,
                positionStable = false,
                deadlineReached = System.currentTimeMillis() >= accountBrowseDeadlineAt
            )
            if (decision == FeedReturnVerificationPolicy.Decision.PAUSE_ROOT_UNAVAILABLE) {
                beginDynamicFeedRefreshRecovery("返回后持续无法读取当前页面")
            } else {
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
            return
        }
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

        val positionMatches = isFeedList &&
            isFeedPositionRestored(accountBrowseSourceSnapshot, currentSnapshot)
        val stability = FeedReturnStability.update(
            previousSnapshot = accountBrowseLastReturnedSnapshot,
            previousSamples = accountBrowseReturnedStableSamples,
            currentSnapshot = currentSnapshot,
            isFeedPage = isFeedList,
            positionMatches = positionMatches,
            requiredSamples = REQUIRED_STABLE_PAGE_SAMPLES
        )
        accountBrowseLastReturnedSnapshot = stability.lastSnapshot
        accountBrowseReturnedStableSamples = stability.stableSamples

        val decision = FeedReturnVerificationPolicy.decide(
            rootAvailable = true,
            isFeedPage = isFeedList,
            positionMatches = positionMatches,
            positionStable = stability.isStable,
            deadlineReached = System.currentTimeMillis() >= accountBrowseDeadlineAt
        )
        when (decision) {
            FeedReturnVerificationPolicy.Decision.RESTORED -> {
                accountBrowseDetailActivityClass = ""
                accountBrowseDetailFingerprint = ""
                accountBrowseProgrammaticActionLabel = ""
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

            FeedReturnVerificationPolicy.Decision.PAUSE_ROOT_UNAVAILABLE -> {
                // The root-available path above cannot produce this outcome.
                beginDynamicFeedRefreshRecovery("返回后持续无法读取当前页面")
                return
            }

            FeedReturnVerificationPolicy.Decision.PAUSE_POSITION_UNCONFIRMED -> {
                beginDynamicFeedRefreshRecovery("已返回动态列表，但无法确认恢复到打开动态前的位置")
                return
            }

            FeedReturnVerificationPolicy.Decision.PAUSE_NOT_FEED_PAGE -> {
                beginDynamicFeedRefreshRecovery("返回后没有重新识别到动态列表")
                return
            }

            FeedReturnVerificationPolicy.Decision.WAIT -> {
                if (isFeedList && !positionMatches) {
                    if (!accountBrowseReturnMismatchLogged) {
                        addLog(
                            "已识别动态列表，但当前可见内容与打开动态前的位置快照不匹配；" +
                                "暂不继续滑动，等待列表位置恢复并稳定"
                        )
                        accountBrowseReturnMismatchLogged = true
                    }
                }
                scheduleAccountBrowseStep(PAGE_STATE_POLL_MS)
            }
        }
    }

    /** A return is accepted only when several list-specific visible nodes and bounds match. */
    private fun isFeedPositionRestored(
        expectedSnapshot: String,
        currentSnapshot: String
    ): Boolean = FeedPositionMatcher.isPositionRestored(
        expectedSnapshot = expectedSnapshot,
        currentSnapshot = currentSnapshot,
        minimumMatches = MIN_RETURN_SNAPSHOT_MATCH_LINES,
        matchRatio = RETURN_SNAPSHOT_MATCH_RATIO
    )

    private fun isMomoDynamicFeedPage(root: AccessibilityNodeInfo): Boolean =
        momoPageInspector.isMomoDynamicFeedPage(root)

    /**
     * Momo's target feed is the top-level Home page's inner "动态" tab.
     * The bottom navigation's "首页" is a different navigation level and must not
     * be used as the feed-tab marker. We identify the top tab row by its visible
     * labels (动态 / 附近 / AI小陌), then validate a visible list below that row.
     */
    private fun inspectMomoFeedReadiness(root: AccessibilityNodeInfo): FeedReadinessInspection =
        momoPageInspector.inspectMomoFeedReadiness(root)

    /**
     * Locate the upper horizontal tab row. Resource IDs are deliberately not required:
     * Momo versions may expose different IDs or omit IDs from accessibility nodes.
     * If another tab is selected, its state and the Dynamic label bounds are both retained
     * so the caller can actively click Dynamic. If selection semantics are unavailable,
     * the same-row navigation labels provide a bounded structural fallback.
     */
    private fun inspectDynamicTabRow(root: AccessibilityNodeInfo): DynamicTabInspection =
        momoPageInspector.inspectDynamicTabRow(root)

    private fun findVisibleFeedContainer(
        root: AccessibilityNodeInfo,
        contentTop: Int
    ): AccessibilityNodeInfo? = momoPageInspector.findVisibleFeedContainer(root, contentTop)

    private fun pageFingerprint(root: AccessibilityNodeInfo): PageFingerprint =
        momoPageInspector.pageFingerprint(root)

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
    ): AccessibilityNodeInfo? = momoFeedGestureController.findScrollableFeedContainer(root)

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

    override fun onInterrupt() {
        pauseAccountBrowse("无障碍服务被系统中断；请检查系统设置后重新启动")
        addLog("无障碍服务被系统中断")
    }

    override fun onDestroy() {
        GestureTrailOverlay.clear()
        restoreMonitorOverlayTouchPassthrough()
        mainHandler.removeCallbacksAndMessages(null)
        accountBrowseScheduler.cancel()
        scanQueued = false
        hideFirstSwipeCountdown()
        if (targetReceiverRegistered) {
            runCatching { unregisterReceiver(targetChangedReceiver) }
            targetReceiverRegistered = false
        }
        if (refreshReceiverRegistered) {
            runCatching { unregisterReceiver(refreshReceiver) }
            refreshReceiverRegistered = false
        }
        if (debugRecoveryTestReceiverRegistered) {
            runCatching { unregisterReceiver(debugRecoveryTestReceiver) }
            debugRecoveryTestReceiverRegistered = false
        }
        if (gestureCalibrationReceiverRegistered) {
            runCatching { unregisterReceiver(gestureCalibrationController.receiver) }
            gestureCalibrationReceiverRegistered = false
        }
        gestureCalibrationController.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DEBUG_SIMULATE_RETURN_POSITION_FAILURE =
            "com.freedom.action.DEBUG_SIMULATE_RETURN_POSITION_FAILURE"

        private const val SCAN_DEBOUNCE_MS = 350L

        private const val ACCOUNT_BROWSE_PREFS = "freedom_main_ui"
        private const val ACCOUNT_BROWSE_KEY = "keep_account_mode_enabled"
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
        private const val HOME_REFRESH_TAP_DURATION_MS = 90L
        private const val HOME_REFRESH_TAP_INTERVAL_MS = 380L
        private const val HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS = 1_800L
        private const val DYNAMIC_FEED_REFRESH_VERIFY_TIMEOUT_MS = 10_000L
        private const val MAX_DYNAMIC_FEED_REFRESH_ATTEMPTS = 2
        private const val REQUIRED_DYNAMIC_REFRESH_STABLE_SAMPLES = 3
        private const val POPUP_VERIFY_POLL_MS = 350L
        private const val POPUP_DISMISS_VERIFY_TIMEOUT_MS = 2_500L
        private const val POPUP_RETRY_DELAY_MS = 900L
        private const val POPUP_BLOCKED_RECHECK_MS = 1_000L
        private const val MAX_POPUP_DISMISS_ATTEMPTS = 2

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
        private const val MIN_DETAIL_NODE_COUNT = 12
        private const val PAGE_FINGERPRINT_MAX_NODES = 500
        private const val FINGERPRINT_SIZE_BUCKET_PX = 24

        private const val MIN_FEED_CARD_WIDTH_RATIO = 0.75f
        private const val MIN_FEED_CARD_HEIGHT_RATIO = 0.18f

        private const val MIN_LIST_WIDTH_RATIO = 0.58f
        private const val MIN_LIST_HEIGHT_RATIO = 0.28f

        private const val LOG_DETAIL_SEPARATOR = "\u001F"
    }
}
