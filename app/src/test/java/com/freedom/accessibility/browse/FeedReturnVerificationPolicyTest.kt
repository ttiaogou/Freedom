package com.freedom.accessibility.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedReturnVerificationPolicyTest {

    @Test
    fun unavailableRootWaitsWhileReturnDeadlineIsPending() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.WAIT,
            rootAvailable = false,
            deadlineReached = false
        )
    }

    @Test
    fun unavailableRootPausesWhenReturnDeadlineExpires() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.PAUSE_ROOT_UNAVAILABLE,
            rootAvailable = false,
            deadlineReached = true
        )
    }

    @Test
    fun nonFeedPageWaitsBeforeDeadline() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.WAIT,
            isFeedPage = false,
            deadlineReached = false
        )
    }

    @Test
    fun nonFeedPagePausesAtDeadline() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.PAUSE_NOT_FEED_PAGE,
            isFeedPage = false,
            deadlineReached = true
        )
    }

    @Test
    fun feedPageWithUnmatchedOrEmptySnapshotWaitsBeforeDeadline() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.WAIT,
            isFeedPage = true,
            positionMatches = false,
            positionStable = false,
            deadlineReached = false
        )
    }

    @Test
    fun feedPageWithUnmatchedOrEmptySnapshotPausesAtDeadline() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.PAUSE_POSITION_UNCONFIRMED,
            isFeedPage = true,
            positionMatches = false,
            positionStable = false,
            deadlineReached = true
        )
    }

    @Test
    fun oneMatchingSampleWaitsUntilConsecutiveStabilityIsConfirmed() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.WAIT,
            isFeedPage = true,
            positionMatches = true,
            positionStable = false,
            deadlineReached = false
        )
    }

    @Test
    fun stableMatchingFeedPageIsAccepted() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.RESTORED,
            isFeedPage = true,
            positionMatches = true,
            positionStable = true,
            deadlineReached = false
        )
    }

    @Test
    fun stableMatchingFeedPageIsAcceptedAtDeadlineBeforeTimeoutPause() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.RESTORED,
            isFeedPage = true,
            positionMatches = true,
            positionStable = true,
            deadlineReached = true
        )
    }

    @Test
    fun wrongPageCanNeverBeAcceptedEvenIfOtherEvidenceIsMarkedStable() {
        assertDecision(
            expected = FeedReturnVerificationPolicy.Decision.PAUSE_NOT_FEED_PAGE,
            isFeedPage = false,
            positionMatches = true,
            positionStable = true,
            deadlineReached = true
        )
    }

    private fun assertDecision(
        expected: FeedReturnVerificationPolicy.Decision,
        rootAvailable: Boolean = true,
        isFeedPage: Boolean = true,
        positionMatches: Boolean = false,
        positionStable: Boolean = false,
        deadlineReached: Boolean
    ) {
        assertEquals(
            expected,
            FeedReturnVerificationPolicy.decide(
                rootAvailable = rootAvailable,
                isFeedPage = isFeedPage,
                positionMatches = positionMatches,
                positionStable = positionStable,
                deadlineReached = deadlineReached
            )
        )
    }
}
