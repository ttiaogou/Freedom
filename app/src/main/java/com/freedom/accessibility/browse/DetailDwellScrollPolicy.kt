package com.freedom.accessibility.browse

/**
 * Filters layout-driven RecyclerView scroll callbacks immediately after a detail page
 * has been confirmed. This is deliberately limited to the short startup grace period.
 */
internal object DetailDwellScrollPolicy {
    const val STARTUP_GRACE_PERIOD_MS = 2_000L

    fun shouldIgnore(
        isScrollEvent: Boolean,
        isDetailActivity: Boolean,
        isDetailDwell: Boolean,
        isRecyclerView: Boolean,
        elapsedSinceDwellStartMs: Long
    ): Boolean =
        isScrollEvent &&
            isDetailActivity &&
            isDetailDwell &&
            isRecyclerView &&
            elapsedSinceDwellStartMs in 0 until STARTUP_GRACE_PERIOD_MS
}
