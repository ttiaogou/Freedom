package com.freedom.accessibility.browse

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Owns Momo navigation target lookup and the low-level accessibility action boundary.
 *
 * It only locates the same visible/clickable nodes and submits a click or global Back action.
 * Navigation attempt counts, result verification, deadlines, retries, logging, and account-browse
 * phase transitions remain owned by FreedomAccessibilityService.
 */
internal class MomoNavigationController(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val performGlobalBack: () -> Boolean,
    private val config: Config
) {
    internal data class Config(
        val maxScanNodes: Int,
        val tabTargetMatchToleranceDp: Int,
        val maxClickTargetHeightRatio: Float,
        val maxAncestorDepth: Int,
        val homeTabContainerViewIdSuffixes: Set<String>
    )

    fun findClickableNodeForLabel(
        root: AccessibilityNodeInfo,
        label: String,
        expectedBounds: Rect,
        minCenterY: Int,
        maxCenterY: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.maxScanNodes) {
                val node = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val nodeLabel = text.ifBlank { description }
                    val sameTarget = nodeLabel == label && node.isVisibleToUser &&
                        bounds.width() > 0 && bounds.height() > 0 &&
                        bounds.centerY() in minCenterY..maxCenterY &&
                        kotlin.math.abs(bounds.centerX() - expectedBounds.centerX()) <= dp(config.tabTargetMatchToleranceDp) &&
                        kotlin.math.abs(bounds.centerY() - expectedBounds.centerY()) <= dp(config.tabTargetMatchToleranceDp)
                    if (sameTarget) {
                        val clickable = findClickableAncestor(node, bounds)
                        if (clickable != null) return clickable
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.maxScanNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return null
    }

    /**
     * The installed Momo build can expose the bottom Home label but omit a useful
     * clickable text ancestor. Its actual click event identifies the tab container
     * by resource ID, so use that observed ID only when it geometrically contains
     * the already-verified Home label bounds and is itself visibly clickable.
     */
    fun findClickableHomeTabContainer(
        root: AccessibilityNodeInfo,
        expectedLabelBounds: Rect,
        minCenterY: Int,
        maxCenterY: Int
    ): AccessibilityNodeInfo? {
        if (config.homeTabContainerViewIdSuffixes.isEmpty()) return null

        val metrics = context.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.40f).toInt()
        val maxHeight = (metrics.heightPixels * 0.18f).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.maxScanNodes) {
                val node = queue.removeFirst()
                visited++
                try {
                    val viewId = runCatching { node.viewIdResourceName.orEmpty() }
                        .getOrDefault("")
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val knownContainer = config.homeTabContainerViewIdSuffixes.any { suffix ->
                        viewId.endsWith(suffix, ignoreCase = true)
                    }
                    val safeTarget = knownContainer &&
                        node.isVisibleToUser &&
                        node.isClickable &&
                        !bounds.isEmpty &&
                        bounds.contains(expectedLabelBounds) &&
                        bounds.centerY() in minCenterY..maxCenterY &&
                        bounds.width() <= maxWidth &&
                        bounds.height() <= maxHeight
                    if (safeTarget) return AccessibilityNodeInfo.obtain(node)

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.maxScanNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().recycle() }
        }
        return null
    }

    fun findClickableAncestor(
        node: AccessibilityNodeInfo,
        labelBounds: Rect
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        val metrics = context.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.98f).toInt()
        val maxHeight = (metrics.heightPixels * config.maxClickTargetHeightRatio).toInt()
        repeat(config.maxAncestorDepth) {
            val candidate = current ?: return null
            val bounds = Rect()
            runCatching { candidate.getBoundsInScreen(bounds) }
            val usable = candidate.isVisibleToUser && candidate.isClickable &&
                bounds.width() > 0 && bounds.width() <= maxWidth &&
                bounds.height() > 0 && bounds.height() <= maxHeight &&
                bounds.contains(labelBounds)
            if (usable) return candidate
            val parent = runCatching { candidate.parent }.getOrNull()
            runCatching { candidate.recycle() }
            current = parent
        }
        runCatching { current?.recycle() }
        return null
    }

    /** Performs ACTION_CLICK and releases the target node exactly once. */
    fun performClickAndRecycle(target: AccessibilityNodeInfo): Boolean {
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { target.recycle() }
        }
    }

    /** Submits the platform global Back action; callers retain their original error handling. */
    fun performBack(): Boolean = performGlobalBack()
}
