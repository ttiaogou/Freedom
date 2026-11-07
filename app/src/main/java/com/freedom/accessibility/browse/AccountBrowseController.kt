package com.freedom.accessibility.browse

/**
 * Coordinates the automatic-browse phase dispatch while leaving Android-specific work
 * with FreedomAccessibilityService.
 *
 * The controller owns the current phase and the per-session evidence state.
 * Page inspection, popup decisions, navigation retries, gesture dispatch, timing and
 * Android-specific operations continue to be coordinated by the service.
 */
internal class AccountBrowseController(
    private val isSessionEnabled: () -> Boolean,
    private val isTouchActive: () -> Boolean,
    private val onResetSession: () -> Unit,
    private val actions: Actions
) {
    internal data class Actions(
        val waitForDynamicTab: () -> Unit,
        val verifyNavigationAction: () -> Unit,
        val refreshDynamicFeed: () -> Unit,
        val verifyDynamicFeedRefresh: () -> Unit,
        val waitForFeedStable: () -> Unit,
        val runFirstSwipeCountdown: () -> Unit,
        val performFeedScroll: () -> Unit,
        val verifyInitialSwipe: () -> Unit,
        val openVisibleFeedCard: () -> Unit,
        val checkDetailPageLoaded: () -> Unit,
        val returnFromDetailPage: () -> Unit,
        val waitForImageBrowserReturn: () -> Unit,
        val waitForDetailRestabilize: () -> Unit,
        val checkFeedListReturned: () -> Unit
    )

    /** Mutable per-session evidence is owned alongside the phase coordinator. */
    val session = AccountBrowseSessionState()

    var phase: AccountBrowsePhase = AccountBrowsePhase.IDLE
        private set

    /** The service remains the caller for all existing phase transitions. */
    fun transitionTo(nextPhase: AccountBrowsePhase) {
        phase = nextPhase
    }

    /**
     * Execute the existing phase-to-action mapping.
     *
     * Keep the gate order identical to the original service implementation: reset when
     * monitoring/mode is disabled, then stop for active touch or PAUSED, then dispatch.
     */
    fun performStep() {
        if (!isSessionEnabled()) {
            onResetSession()
            return
        }
        if (isTouchActive() || phase == AccountBrowsePhase.PAUSED) return

        when (phase) {
            AccountBrowsePhase.IDLE,
            AccountBrowsePhase.PAUSED -> return
            AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB -> actions.waitForDynamicTab()
            AccountBrowsePhase.VERIFY_NAVIGATION_ACTION -> actions.verifyNavigationAction()
            AccountBrowsePhase.REFRESH_DYNAMIC_FEED -> actions.refreshDynamicFeed()
            AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH -> actions.verifyDynamicFeedRefresh()
            AccountBrowsePhase.WAIT_FOR_FEED_STABLE -> actions.waitForFeedStable()
            AccountBrowsePhase.FIRST_SWIPE_COUNTDOWN -> actions.runFirstSwipeCountdown()
            AccountBrowsePhase.WAIT_FOR_LIST_SCROLL -> actions.performFeedScroll()
            AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE -> actions.verifyInitialSwipe()
            AccountBrowsePhase.WAIT_FOR_CARD_OPEN,
            AccountBrowsePhase.WAIT_FOR_SAFE_CARD -> actions.openVisibleFeedCard()
            AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD -> actions.checkDetailPageLoaded()
            AccountBrowsePhase.DETAIL_DWELL -> actions.returnFromDetailPage()
            AccountBrowsePhase.WAIT_FOR_IMAGE_BROWSER_RETURN -> actions.waitForImageBrowserReturn()
            AccountBrowsePhase.WAIT_FOR_DETAIL_RESTABILIZE -> actions.waitForDetailRestabilize()
            AccountBrowsePhase.WAIT_FOR_LIST_RETURN -> actions.checkFeedListReturned()
        }
    }

    /**
     * Reset session state while preserving the former Service side-effect order.
     * External Android/UI cleanup is supplied by the caller at the original boundaries.
     */
    fun resetSession(
        beforeReset: () -> Unit,
        afterReset: () -> Unit
    ) {
        beforeReset()
        phase = AccountBrowsePhase.IDLE
        session.reset()
        afterReset()
    }

    /** Centralize pause-state ordering; Android-specific cleanup remains callback-owned. */
    fun pauseSession(
        reason: String,
        allowManualResume: Boolean,
        capturePausedContext: (AccountBrowsePhase) -> Unit,
        beforePause: () -> Unit,
        hideCountdown: () -> Unit,
        onPauseReasonChanged: (String) -> Unit
    ) {
        if (allowManualResume && phase != AccountBrowsePhase.PAUSED) {
            capturePausedContext(phase)
        }
        beforePause()
        session.manualResumeAllowed = allowManualResume
        session.gestureInFlight = false
        session.countdownRemaining = 0
        hideCountdown()
        phase = AccountBrowsePhase.PAUSED
        session.deadlineAt = 0L
        if (session.pauseReason != reason) {
            session.pauseReason = reason
            onPauseReasonChanged(reason)
        }
    }

    /** Record manual takeover while already paused, without replacing the first snapshot. */
    fun recordManualTakeoverWhilePaused(
        activityClass: String,
        sourceFingerprint: String,
        detailFingerprint: String,
        imageBrowserActive: Boolean,
        lastProgrammaticAction: String,
        currentTimeMillis: () -> Long
    ) {
        session.manualResumeAllowed = true
        if (session.pausedContext == null) {
            session.pausedContext = ManualResumeContext(
                phaseName = "PAUSED",
                activityClass = activityClass,
                sourceFingerprint = sourceFingerprint,
                detailFingerprint = detailFingerprint,
                imageBrowserActive = imageBrowserActive,
                lastProgrammaticAction = lastProgrammaticAction,
                capturedAt = currentTimeMillis()
            )
        }
    }

    /**
     * Apply the existing post-touch quiet-period checks and state reset before navigation.
     * Predicates are lambdas so they are evaluated lazily in their original branch order.
     */
    fun resumeAfterManualTouch(
        isMonitoringRunning: () -> Boolean,
        isBrowseModeEnabled: () -> Boolean,
        quietPeriodMs: Long,
        initialDynamicTabWaitTimeoutMs: Long,
        pageStatePollMs: Long,
        currentTimeMillis: () -> Long,
        onLog: (String) -> Unit,
        scheduleStep: (Long) -> Unit
    ) {
        when {
            !session.manualResumeAllowed -> Unit
            session.touchActive -> onLog("手动恢复检查未执行：仍检测到屏幕触摸，继续等待用户操作结束")
            !isMonitoringRunning() -> onLog("手动恢复检查未执行：监听已停止")
            !isBrowseModeEnabled() -> onLog("手动恢复检查未执行：自动浏览模式已关闭")
            else -> {
                session.manualResumeAllowed = false
                val context = session.pausedContext
                onLog(
                    "用户最后一次屏幕操作后已静默 ${quietPeriodMs / 1000.0} 秒；" +
                        "开始基于暂停上下文重新分析当前页面；暂停前阶段=${context?.phaseName ?: "未知"}，" +
                        "暂停前 Activity=${context?.activityClass?.ifBlank { "未知" } ?: "未知"}"
                )
                session.pauseReason = ""
                session.sourceFingerprint = ""
                session.detailFingerprint = ""
                session.lastStableFingerprint = ""
                session.stableSamples = 0
                session.navigationActionKey = ""
                session.navigationBeforeActivity = ""
                session.navigationBeforeFingerprint = ""
                session.navigationActionDeadlineAt = 0L
                session.navigationActionAttempts = 0
                session.recoveryBackAttempts = 0
                session.recoveryHomeClickAttempts = 0
                session.recoveryDynamicClickAttempts = 0
                session.dynamicTabClickConfirmed = false
                phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
                session.deadlineAt = currentTimeMillis() + initialDynamicTabWaitTimeoutMs
                session.lastDynamicTabWaitReason = ""
                onLog("手动恢复已进入主动导航：先识别页面层级，再返回必要的上级页面，主动定位并验证‘动态’标签")
                scheduleStep(pageStatePollMs)
            }
        }
    }

    /** Move only recovery-state preparation; Android overlay and Handler operations stay outside. */
    fun beginNavigationRecovery(
        reason: String,
        beforeRecovery: () -> Unit,
        initialDynamicTabWaitTimeoutMs: Long,
        pageStatePollMs: Long,
        currentTimeMillis: () -> Long,
        onStarted: (String) -> Unit,
        scheduleStep: (Long) -> Unit
    ) {
        if (session.pausedContext == null) {
            session.pausedContext = ManualResumeContext(
                phaseName = phase.name,
                activityClass = session.currentActivityClass,
                sourceFingerprint = session.sourceFingerprint,
                detailFingerprint = session.detailFingerprint,
                imageBrowserActive = session.imageBrowserActive,
                lastProgrammaticAction = session.programmaticActionLabel,
                capturedAt = currentTimeMillis()
            )
        }
        beforeRecovery()
        session.pauseReason = reason
        session.manualResumeAllowed = false
        session.navigationActionKey = ""
        session.navigationBeforeActivity = ""
        session.navigationBeforeFingerprint = ""
        session.navigationActionDeadlineAt = 0L
        session.navigationActionAttempts = 0
        session.recoveryBackAttempts = 0
        session.recoveryHomeClickAttempts = 0
        session.recoveryDynamicClickAttempts = 0
        session.dynamicTabClickConfirmed = false
        session.lastDynamicTabWaitReason = ""
        phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB
        session.deadlineAt = currentTimeMillis() + initialDynamicTabWaitTimeoutMs
        onStarted(reason)
        scheduleStep(pageStatePollMs)
    }

}
