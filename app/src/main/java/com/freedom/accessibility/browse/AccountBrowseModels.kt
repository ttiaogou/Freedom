package com.freedom.accessibility.browse

import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

    // Read the existing main-screen checkbox without changing its storage key.
internal enum class AccountBrowsePhase {
        IDLE,
        WAIT_FOR_DYNAMIC_TAB,
        VERIFY_NAVIGATION_ACTION,
        WAIT_FOR_FEED_STABLE,
        FIRST_SWIPE_COUNTDOWN,
        WAIT_FOR_LIST_SCROLL,
        WAIT_FOR_SCROLL_SETTLE,
        WAIT_FOR_CARD_OPEN,
        WAIT_FOR_SAFE_CARD,
        WAIT_FOR_DETAIL_LOAD,
        DETAIL_DWELL,
        WAIT_FOR_IMAGE_BROWSER_RETURN,
        WAIT_FOR_DETAIL_RESTABILIZE,
        WAIT_FOR_LIST_RETURN,
        PAUSED
    }

internal data class PageFingerprint(
        val signature: String,
        val nodeCount: Int
    )

internal data class FeedReadinessInspection(
        val fingerprint: PageFingerprint?,
        val reason: String
    )

internal data class TabLabelEvidence(
        val label: String,
        val bounds: Rect,
        val selected: Boolean
    )

internal data class DynamicTabRowEvidence(
        val contentTop: Int,
        val diagnostic: String,
        val dynamicBounds: Rect,
        val dynamicSelected: Boolean,
        val selectionKnown: Boolean
    )

internal data class HomeBottomNavEvidence(
        val homeBounds: Rect,
        val homeSelected: Boolean,
        val selectionKnown: Boolean,
        val diagnostic: String
    )

internal data class ManualResumeContext(
        val phaseName: String,
        val activityClass: String,
        val sourceFingerprint: String,
        val detailFingerprint: String,
        val imageBrowserActive: Boolean,
        val lastProgrammaticAction: String,
        val capturedAt: Long
    )

internal data class DynamicTabInspection(
        val evidence: DynamicTabRowEvidence?,
        val reason: String
    )

internal data class PopupInspection(
        val container: AccessibilityNodeInfo?,
        val closeTarget: AccessibilityNodeInfo?,
        val dismissable: Boolean,
        val critical: Boolean,
        val signature: String,
        val description: String
    )

internal data class DetailPageIdentityInspection(
        val isFeedPage: Boolean,
        val identityConfirmed: Boolean,
        val fingerprint: PageFingerprint,
        val reason: String
    )

internal data class FeedGestureTarget(
        val bounds: Rect,
        val className: String
    )

internal data class ScrollGestureProfile(
        val durationMs: Long,
        val curveOffsetRatio: Float,
        val microPerturbationFrequencyHz: Float
    )

internal data class ScrollGesturePlan(
        val path: Path,
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int
    )
