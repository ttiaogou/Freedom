package com.freedom.accessibility.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicTabStartupScrollPolicyTest {
    private fun shouldIgnore(
        elapsedMs: Long = 500L,
        isScrollEvent: Boolean = true,
        isDynamicTabWait: Boolean = true,
        isMainActivity: Boolean = true,
        isRecyclerView: Boolean = true,
        hasTouchEvidence: Boolean = false
    ): Boolean = DynamicTabStartupScrollPolicy.shouldIgnore(
        isScrollEvent = isScrollEvent,
        isDynamicTabWait = isDynamicTabWait,
        isMainActivity = isMainActivity,
        isRecyclerView = isRecyclerView,
        elapsedSinceWaitStartedMs = elapsedMs,
        hasTouchEvidence = hasTouchEvidence
    )

    @Test
    fun ignoresStartupRecyclerViewScrollWithoutTouchEvidence() {
        assertTrue(shouldIgnore())
    }

    @Test
    fun ignoresScrollImmediatelyBeforeGracePeriodEnds() {
        assertTrue(shouldIgnore(elapsedMs = 1_999L))
    }

    @Test
    fun doesNotIgnoreScrollAtGracePeriodBoundary() {
        assertFalse(shouldIgnore(elapsedMs = 2_000L))
    }

    @Test
    fun doesNotIgnoreScrollAfterGracePeriod() {
        assertFalse(shouldIgnore(elapsedMs = 2_001L))
    }

    @Test
    fun doesNotIgnoreWhenGlobalTouchEvidenceExists() {
        assertFalse(shouldIgnore(hasTouchEvidence = true))
    }

    @Test
    fun doesNotIgnoreOutsideDynamicTabIdentificationPhase() {
        assertFalse(shouldIgnore(isDynamicTabWait = false))
    }

    @Test
    fun doesNotIgnoreOutsideMainActivity() {
        assertFalse(shouldIgnore(isMainActivity = false))
    }

    @Test
    fun doesNotIgnoreNonRecyclerViewEvents() {
        assertFalse(shouldIgnore(isRecyclerView = false))
    }

    @Test
    fun doesNotIgnoreNonScrollEvents() {
        assertFalse(shouldIgnore(isScrollEvent = false))
    }

    @Test
    fun doesNotIgnoreNegativeElapsedTime() {
        assertFalse(shouldIgnore(elapsedMs = -1L))
    }
}
