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
}
