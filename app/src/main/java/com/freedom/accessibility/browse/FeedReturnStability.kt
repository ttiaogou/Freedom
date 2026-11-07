package com.freedom.accessibility.browse

/** Pure stability counter for post-detail feed-return verification. */
internal object FeedReturnStability {
    data class State(
        val lastSnapshot: String,
        val stableSamples: Int,
        val isStable: Boolean
    )

    fun update(
        previousSnapshot: String,
        previousSamples: Int,
        currentSnapshot: String,
        isFeedPage: Boolean,
        positionMatches: Boolean,
        requiredSamples: Int
    ): State {
        if (requiredSamples < 1 || !isFeedPage || !positionMatches || currentSnapshot.isBlank()) {
            return State(lastSnapshot = "", stableSamples = 0, isStable = false)
        }

        val samples = if (currentSnapshot == previousSnapshot) {
            previousSamples.coerceAtLeast(0) + 1
        } else {
            1
        }
        return State(
            lastSnapshot = currentSnapshot,
            stableSamples = samples,
            isStable = samples >= requiredSamples
        )
    }
}
