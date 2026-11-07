package com.freedom.accessibility.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedReturnStabilityTest {

    @Test
    fun firstMatchingSnapshotIsNotEnough() {
        val state = FeedReturnStability.update(
            previousSnapshot = "",
            previousSamples = 0,
            currentSnapshot = "feed-A",
            isFeedPage = true,
            positionMatches = true,
            requiredSamples = 2
        )

        assertEquals("feed-A", state.lastSnapshot)
        assertEquals(1, state.stableSamples)
        assertFalse(state.isStable)
    }

    @Test
    fun sameMatchingSnapshotTwiceRestoresPosition() {
        val state = FeedReturnStability.update(
            previousSnapshot = "feed-A",
            previousSamples = 1,
            currentSnapshot = "feed-A",
            isFeedPage = true,
            positionMatches = true,
            requiredSamples = 2
        )

        assertEquals(2, state.stableSamples)
        assertTrue(state.isStable)
    }

    @Test
    fun changedSnapshotStartsANewStabilitySequence() {
        val state = FeedReturnStability.update(
            previousSnapshot = "feed-A",
            previousSamples = 1,
            currentSnapshot = "feed-B",
            isFeedPage = true,
            positionMatches = true,
            requiredSamples = 2
        )

        assertEquals("feed-B", state.lastSnapshot)
        assertEquals(1, state.stableSamples)
        assertFalse(state.isStable)
    }

    @Test
    fun nonFeedPageResetsPreviousMatchingSample() {
        val state = FeedReturnStability.update(
            previousSnapshot = "feed-A",
            previousSamples = 1,
            currentSnapshot = "detail-page",
            isFeedPage = false,
            positionMatches = false,
            requiredSamples = 2
        )

        assertEquals("", state.lastSnapshot)
        assertEquals(0, state.stableSamples)
        assertFalse(state.isStable)
    }

    @Test
    fun temporarilyUnreadableSnapshotResetsPreviousMatchingSample() {
        val state = FeedReturnStability.update(
            previousSnapshot = "feed-A",
            previousSamples = 1,
            currentSnapshot = "",
            isFeedPage = true,
            positionMatches = false,
            requiredSamples = 2
        )

        assertEquals("", state.lastSnapshot)
        assertEquals(0, state.stableSamples)
        assertFalse(state.isStable)
    }

    @Test
    fun mismatchedPositionCannotAccumulateStableSamples() {
        val state = FeedReturnStability.update(
            previousSnapshot = "feed-A",
            previousSamples = 1,
            currentSnapshot = "feed-A",
            isFeedPage = true,
            positionMatches = false,
            requiredSamples = 2
        )

        assertEquals(0, state.stableSamples)
        assertFalse(state.isStable)
    }
}
