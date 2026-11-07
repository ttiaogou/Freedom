package com.freedom.accessibility.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFeedRecoveryStartupScrollPolicyTest {
    private fun shouldIgnore(
        isScrollEvent: Boolean = true,
        isRecoveryActive: Boolean = true,
        isWaitingForDynamicTab: Boolean = true,
        isMainActivity: Boolean = true,
        isRecyclerView: Boolean = true,
        hasTouchEvidence: Boolean = false
    ): Boolean = DynamicFeedRecoveryStartupScrollPolicy.shouldIgnore(
        isScrollEvent = isScrollEvent,
        isRecoveryActive = isRecoveryActive,
        isWaitingForDynamicTab = isWaitingForDynamicTab,
        isMainActivity = isMainActivity,
        isRecyclerView = isRecyclerView,
        hasTouchEvidence = hasTouchEvidence
    )

    @Test
    fun ignoresRecyclerViewStartupScrollDuringActiveRecoveryWithoutTouchEvidence() {
        assertTrue(shouldIgnore())
    }

    @Test
    fun doesNotIgnoreWhenRecoveryIsNotActive() {
        assertFalse(shouldIgnore(isRecoveryActive = false))
    }

    @Test
    fun doesNotIgnoreOutsideDynamicTabWait() {
        assertFalse(shouldIgnore(isWaitingForDynamicTab = false))
    }

    @Test
    fun doesNotIgnoreOutsideMomoMainActivity() {
        assertFalse(shouldIgnore(isMainActivity = false))
    }

    @Test
    fun doesNotIgnoreNonRecyclerViewScrollEvents() {
        assertFalse(shouldIgnore(isRecyclerView = false))
    }

    @Test
    fun doesNotIgnoreNonScrollEvents() {
        assertFalse(shouldIgnore(isScrollEvent = false))
    }

    @Test
    fun doesNotIgnoreWhenIndependentTouchEvidenceExists() {
        assertFalse(shouldIgnore(hasTouchEvidence = true))
    }
}