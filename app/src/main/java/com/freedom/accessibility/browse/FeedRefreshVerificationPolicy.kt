package com.freedom.accessibility.browse

/** Pure decision rules for accepting a refreshed dynamic feed as a new browsing baseline. */
internal object FeedRefreshVerificationPolicy {
    const val MIN_RECYCLER_CONTENT_CHANGE_EVENTS = 2

    fun hasReloadEvidence(
        snapshotChanged: Boolean,
        recyclerContentChangeEvents: Int
    ): Boolean = snapshotChanged ||
        recyclerContentChangeEvents >= MIN_RECYCLER_CONTENT_CHANGE_EVENTS

    fun canResumeBrowsing(
        isDynamicFeedPage: Boolean,
        snapshotChanged: Boolean,
        recyclerContentChangeEvents: Int,
        stableSamples: Int,
        requiredStableSamples: Int
    ): Boolean {
        if (!isDynamicFeedPage || requiredStableSamples < 1) return false
        if (stableSamples < requiredStableSamples) return false
        return hasReloadEvidence(snapshotChanged, recyclerContentChangeEvents)
    }
}
