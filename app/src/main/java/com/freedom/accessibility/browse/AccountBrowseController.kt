package com.freedom.accessibility.browse

/**
 * Coordinates the automatic-browse phase dispatch while leaving Android-specific work
 * with FreedomAccessibilityService.
 *
 * The controller owns the current phase and the single scheduled-step dispatch decision.
 * Page inspection, popup decisions, navigation retries, gesture dispatch, timing and
 * session-specific evidence continue to be supplied/owned by the service.
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
}
