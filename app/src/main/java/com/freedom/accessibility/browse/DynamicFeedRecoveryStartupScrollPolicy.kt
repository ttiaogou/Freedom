package com.freedom.accessibility.browse

/**
 * A RecyclerView may emit layout-driven scroll callbacks while Momo's main page is
 * still being created. During the explicit feed-refresh recovery startup, such a
 * callback is not enough to infer user input unless independent touch evidence exists.
 */
internal object DynamicFeedRecoveryStartupScrollPolicy {
    fun shouldIgnore(
        isScrollEvent: Boolean,
        isRecoveryActive: Boolean,
        isWaitingForDynamicTab: Boolean,
        isMainActivity: Boolean,
        isRecyclerView: Boolean,
        hasTouchEvidence: Boolean
    ): Boolean =
        isScrollEvent &&
            isRecoveryActive &&
            isWaitingForDynamicTab &&
            isMainActivity &&
            isRecyclerView &&
            !hasTouchEvidence
}