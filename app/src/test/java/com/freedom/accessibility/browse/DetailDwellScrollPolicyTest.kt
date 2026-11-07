package com.freedom.accessibility.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailDwellScrollPolicyTest {

    @Test
    fun ignoresRecyclerViewScrollImmediatelyAfterDetailDwellStarts() {
        assertTrue(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 500L
            )
        )
    }

    @Test
    fun ignoresScrollUntilGracePeriodBoundary() {
        assertTrue(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 1_999L
            )
        )
    }

    @Test
    fun doesNotIgnoreScrollAtOrAfterGracePeriodBoundary() {
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 2_000L
            )
        )
    }

    @Test
    fun doesNotIgnoreScrollOutsideDetailDwell() {
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = false,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 500L
            )
        )
    }

    @Test
    fun doesNotIgnoreNonRecyclerViewOrNonScrollEvents() {
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = false,
                elapsedSinceDwellStartMs = 500L
            )
        )
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = false,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 500L
            )
        )
    }

    @Test
    fun doesNotIgnoreEventsFromOtherActivitiesOrNegativeElapsedTime() {
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = false,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = 500L
            )
        )
        assertFalse(
            DetailDwellScrollPolicy.shouldIgnore(
                isScrollEvent = true,
                isDetailActivity = true,
                isDetailDwell = true,
                isRecyclerView = true,
                elapsedSinceDwellStartMs = -1L
            )
        )
    }
}
