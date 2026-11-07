package com.freedom.accessibility.browse

import kotlin.math.ceil

/**
 * Compares the visible feed snapshot captured before opening a card with the
 * snapshot observed after returning to the feed.
 *
 * This class deliberately contains no Android APIs so its matching rule can be
 * verified by local JVM unit tests.
 */
internal object FeedPositionMatcher {

    fun isPositionRestored(
        expectedSnapshot: String,
        currentSnapshot: String,
        minimumMatches: Int,
        matchRatio: Double
    ): Boolean {
        if (expectedSnapshot.isBlank() || currentSnapshot.isBlank()) return false
        if (minimumMatches < 1 || matchRatio <= 0.0 || matchRatio > 1.0) return false

        val expected = expectedSnapshot
            .split(SNAPSHOT_SEPARATOR)
            .filter { it.isNotBlank() }
            .toSet()
        val current = currentSnapshot
            .split(SNAPSHOT_SEPARATOR)
            .filter { it.isNotBlank() }
            .toSet()

        // The original snapshot is the reference point. A short or incomplete
        // current snapshot must not lower the number of required matches.
        if (expected.size < minimumMatches) return false

        val matchingCount = expected.count { it in current }
        val requiredMatches = maxOf(
            minimumMatches,
            ceil(expected.size * matchRatio).toInt()
        )
        return matchingCount >= requiredMatches
    }

    private const val SNAPSHOT_SEPARATOR = " | "
}
