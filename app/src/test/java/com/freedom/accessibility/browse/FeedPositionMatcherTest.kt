package com.freedom.accessibility.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPositionMatcherTest {

    private val expectedLines = listOf(
        "TextView:动态内容一@0,300,400,420",
        "TextView:动态内容二@0,430,400,550",
        "TextView:动态内容三@0,560,400,680",
        "TextView:动态内容四@0,690,400,810",
        "TextView:动态内容五@0,820,400,940",
        "TextView:动态内容六@0,950,400,1070",
        "TextView:动态内容七@0,1080,400,1200",
        "TextView:动态内容八@0,1210,400,1330"
    )

    @Test
    fun threeMatchingRowsCannotRestoreAnEightRowSnapshot() {
        val current = snapshot(expectedLines.take(3))

        assertFalse(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = snapshot(expectedLines),
                currentSnapshot = current,
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun fiveOfEightMatchingRowsAreBelowTheSeventyPercentThreshold() {
        assertFalse(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = snapshot(expectedLines),
                currentSnapshot = snapshot(expectedLines.take(5)),
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun sixOfEightMatchingRowsMeetTheSeventyPercentThreshold() {
        assertTrue(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = snapshot(expectedLines),
                currentSnapshot = snapshot(expectedLines.take(6)),
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun threeOfThreeMatchingRowsMeetMinimumAndRatioThresholds() {
        val threeRows = snapshot(expectedLines.take(3))

        assertTrue(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = threeRows,
                currentSnapshot = threeRows,
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun fewerThanMinimumOriginalRowsCannotRestorePosition() {
        val twoRows = snapshot(expectedLines.take(2))

        assertFalse(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = twoRows,
                currentSnapshot = twoRows,
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun blankSnapshotsNeverRestorePosition() {
        val threeRows = snapshot(expectedLines.take(3))

        assertFalse(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = "",
                currentSnapshot = threeRows,
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
        assertFalse(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = threeRows,
                currentSnapshot = "   ",
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    @Test
    fun additionalCurrentRowsDoNotReduceOriginalSnapshotThreshold() {
        val current = snapshot(expectedLines.take(6) + listOf("TextView:新内容@0,1400,400,1520"))

        assertTrue(
            FeedPositionMatcher.isPositionRestored(
                expectedSnapshot = snapshot(expectedLines),
                currentSnapshot = current,
                minimumMatches = 3,
                matchRatio = 0.70
            )
        )
    }

    private fun snapshot(lines: List<String>): String = lines.joinToString(" | ")
}
