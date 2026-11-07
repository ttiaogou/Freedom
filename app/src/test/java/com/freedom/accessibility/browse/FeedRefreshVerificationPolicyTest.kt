package com.freedom.accessibility.browse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRefreshVerificationPolicyTest {

    @Test
    fun noListChangeAndUnchangedSnapshotIsNotRefreshEvidence() {
        assertFalse(
            FeedRefreshVerificationPolicy.hasReloadEvidence(
                snapshotChanged = false,
                recyclerContentChangeEvents = 0
            )
        )
    }

    @Test
    fun changedVisibleSnapshotIsRefreshEvidence() {
        assertTrue(
            FeedRefreshVerificationPolicy.hasReloadEvidence(
                snapshotChanged = true,
                recyclerContentChangeEvents = 0
            )
        )
    }

    @Test
    fun repeatedRecyclerContentChangesCanConfirmReloadWhenSnapshotIsUnchanged() {
        assertTrue(
            FeedRefreshVerificationPolicy.hasReloadEvidence(
                snapshotChanged = false,
                recyclerContentChangeEvents = 2
            )
        )
    }

    @Test
    fun oneContentChangeWithoutSnapshotChangeIsInsufficient() {
        assertFalse(
            FeedRefreshVerificationPolicy.hasReloadEvidence(
                snapshotChanged = false,
                recyclerContentChangeEvents = 1
            )
        )
    }

    @Test
    fun browsingCannotResumeBeforeTheListIsStable() {
        assertFalse(
            FeedRefreshVerificationPolicy.canResumeBrowsing(
                isDynamicFeedPage = true,
                snapshotChanged = true,
                recyclerContentChangeEvents = 1,
                stableSamples = 2,
                requiredStableSamples = 3
            )
        )
    }

    @Test
    fun browsingCannotResumeOnAnUnrecognizedPage() {
        assertFalse(
            FeedRefreshVerificationPolicy.canResumeBrowsing(
                isDynamicFeedPage = false,
                snapshotChanged = true,
                recyclerContentChangeEvents = 3,
                stableSamples = 3,
                requiredStableSamples = 3
            )
        )
    }

    @Test
    fun changedAndStableDynamicFeedCanBecomeTheNewBaseline() {
        assertTrue(
            FeedRefreshVerificationPolicy.canResumeBrowsing(
                isDynamicFeedPage = true,
                snapshotChanged = true,
                recyclerContentChangeEvents = 1,
                stableSamples = 3,
                requiredStableSamples = 3
            )
        )
    }

    @Test
    fun stableDynamicFeedWithRepeatedContentChangesCanBecomeTheNewBaseline() {
        assertTrue(
            FeedRefreshVerificationPolicy.canResumeBrowsing(
                isDynamicFeedPage = true,
                snapshotChanged = false,
                recyclerContentChangeEvents = 2,
                stableSamples = 3,
                requiredStableSamples = 3
            )
        )
    }
}
