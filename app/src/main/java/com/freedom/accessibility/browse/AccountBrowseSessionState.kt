package com.freedom.accessibility.browse

/**
 * Mutable evidence and runtime state for one automatic-browse session.
 *
 * This class intentionally contains data only. State transitions, timing decisions,
 * Android accessibility operations, and reset ordering remain in their existing owners.
 */
internal class AccountBrowseSessionState {
    // Pause, manual takeover, and touch-event evidence
    var pauseReason = ""

    // Navigation and page-readiness evidence
    var lastDynamicTabWaitReason = ""
    var dynamicTabWaitStartedAt = 0L
    var deadlineAt = 0L
    var sourceFingerprint = ""
    var detailFingerprint = ""
    var detailActivityClass = ""
    var lastStableFingerprint = ""
    var stableSamples = 0
    var touchActive = false
    var manualResumeAllowed = false
    var touchStartedDuringGesture = false
    var touchEndedDuringGesture = false
    var gestureInFlight = false
    var scrollEventSeen = false
    var scrollEventCount = 0
    var lastScrollEventAt = 0L
    var swipeCount = 0
    var fallbackScrollAttempted = false
    var sourceSnapshot = ""
    var returnedStableSamples = 0
    var lastReturnedSnapshot = ""
    var returnMismatchLogged = false
    var countdownRemaining = 0
    var overlayPassthroughActive = false
    var currentActivityClass = ""
    var imageBrowserReturnActivityClass = ""
    var imageBrowserActive = false
    var detailDwellDueAt = 0L
    var detailDwellStartedAt = 0L
    var detailDwellRemainingMs = 0L
    var ignoreManualEventsUntil = 0L
    var programmaticActionLabel = ""
    var safeCardRecheckCount = 0
    var lastGlobalTouchEventAt = 0L
    var lastGlobalTouchEventName = ""
    var lastGlobalTouchEventOrigin = ""
    var pausedContext: ManualResumeContext? = null
    var navigationActionKey = ""
    var navigationBeforeActivity = ""
    var navigationBeforeFingerprint = ""
    var navigationActionDeadlineAt = 0L
    var navigationActionAttempts = 0
    var recoveryBackAttempts = 0
    var recoveryHomeClickAttempts = 0
    var recoveryDynamicClickAttempts = 0
    var dynamicTabClickConfirmed = false
    var notTargetForegroundLogged = false
    var rootUnavailableLogged = false

    // Popup inspection/verification evidence
    var lastPopupSignature = ""
    var popupDismissPending = false
    var popupDismissDeadlineAt = 0L
    var popupNextAttemptAt = 0L
    var popupActionAttempts = 0
    var popupExhaustedLogged = false

    /** Restore session fields reset by the former Service body. Overlay passthrough remains owned by the WindowManager cleanup callback. */
    fun reset() {
        pauseReason = ""
        lastDynamicTabWaitReason = ""
        dynamicTabWaitStartedAt = 0L
        deadlineAt = 0L
        sourceFingerprint = ""
        sourceSnapshot = ""
        returnedStableSamples = 0
        lastReturnedSnapshot = ""
        returnMismatchLogged = false
        detailFingerprint = ""
        detailActivityClass = ""
        lastStableFingerprint = ""
        stableSamples = 0
        lastPopupSignature = ""
        popupDismissPending = false
        popupDismissDeadlineAt = 0L
        popupNextAttemptAt = 0L
        popupActionAttempts = 0
        popupExhaustedLogged = false
        touchActive = false
        manualResumeAllowed = false
        touchStartedDuringGesture = false
        touchEndedDuringGesture = false
        gestureInFlight = false
        scrollEventSeen = false
        scrollEventCount = 0
        lastScrollEventAt = 0L
        swipeCount = 0
        fallbackScrollAttempted = false
        countdownRemaining = 0
        currentActivityClass = ""
        imageBrowserReturnActivityClass = ""
        imageBrowserActive = false
        detailDwellDueAt = 0L
        detailDwellStartedAt = 0L
        detailDwellRemainingMs = 0L
        ignoreManualEventsUntil = 0L
        programmaticActionLabel = ""
        safeCardRecheckCount = 0
        lastGlobalTouchEventAt = 0L
        lastGlobalTouchEventName = ""
        lastGlobalTouchEventOrigin = ""
        pausedContext = null
        navigationActionKey = ""
        navigationBeforeActivity = ""
        navigationBeforeFingerprint = ""
        navigationActionDeadlineAt = 0L
        navigationActionAttempts = 0
        recoveryBackAttempts = 0
        recoveryHomeClickAttempts = 0
        recoveryDynamicClickAttempts = 0
        dynamicTabClickConfirmed = false
        notTargetForegroundLogged = false
        rootUnavailableLogged = false
    }
}
