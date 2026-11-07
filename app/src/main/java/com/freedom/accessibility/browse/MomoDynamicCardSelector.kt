package com.freedom.accessibility.browse

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * Identifies a safe, visible Momo dynamic card and excludes identifiable live-room cards.
 * Only direct children of the confirmed feed list are considered clickable card candidates.
 */
object MomoDynamicCardSelector {

    data class SelectionResult(
        val node: AccessibilityNodeInfo?,
        val inspectedCardCount: Int,
        val skippedLiveStreamCount: Int,
        val rejectedWithoutContentCount: Int,
        val reason: String
    )

    private data class CardContentInspection(
        val isLiveStream: Boolean,
        val hasVisibleContent: Boolean
    )

    private val liveRoomMarkers = listOf(
        "正在直播",
        "直播中",
        "直播间",
        "进入直播间",
        "直播回放",
        "主播开播",
        "开播中"
    )

    private const val MAX_CONTENT_NODES_PER_CARD = 160

    /** Returns an owned node copy; the caller must recycle [SelectionResult.node]. */
    fun select(
        scrollNode: AccessibilityNodeInfo,
        minWidthPx: Int,
        minHeightPx: Int
    ): SelectionResult {
        val listBounds = Rect()
        runCatching { scrollNode.getBoundsInScreen(listBounds) }
        if (listBounds.isEmpty) {
            return SelectionResult(null, 0, 0, 0, "动态列表边界不可用")
        }

        val childCount = runCatching { scrollNode.childCount }.getOrDefault(0)
        var inspected = 0
        var skippedLive = 0
        var rejectedWithoutContent = 0
        var bestNode: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        var bestArea = 0L

        for (index in 0 until childCount) {
            val child = runCatching { scrollNode.getChild(index) }.getOrNull() ?: continue
            try {
                val visible = runCatching { child.isVisibleToUser }.getOrDefault(false)
                val clickable = runCatching { child.isClickable }.getOrDefault(false)
                val enabled = runCatching { child.isEnabled }.getOrDefault(false)
                val editable = runCatching { child.isEditable }.getOrDefault(false)
                if (!visible || !clickable || !enabled || editable) continue

                val bounds = Rect()
                runCatching { child.getBoundsInScreen(bounds) }
                if (bounds.isEmpty || bounds.width() < minWidthPx || bounds.height() < minHeightPx) continue
                if (!listBounds.contains(bounds)) continue

                inspected++
                val content = inspectCardContent(child)
                if (content.isLiveStream) {
                    skippedLive++
                    continue
                }
                if (!content.hasVisibleContent) {
                    rejectedWithoutContent++
                    continue
                }

                val area = bounds.width().toLong() * bounds.height().toLong()
                if (bounds.top < bestTop || (bounds.top == bestTop && area > bestArea)) {
                    runCatching { bestNode?.recycle() }
                    bestNode = AccessibilityNodeInfo.obtain(child)
                    bestTop = bounds.top
                    bestArea = area
                }
            } finally {
                runCatching { child.recycle() }
            }
        }

        val reason = when {
            bestNode != null -> "已找到符合尺寸、可见性、边界和内容条件的普通动态"
            skippedLive > 0 && rejectedWithoutContent == 0 -> "可见候选均为疑似直播间动态，已全部跳过"
            inspected == 0 -> "列表中没有满足尺寸和可点击条件的完整卡片"
            else -> "候选卡片缺少可验证的可见内容，未执行点击"
        }
        return SelectionResult(
            node = bestNode,
            inspectedCardCount = inspected,
            skippedLiveStreamCount = skippedLive,
            rejectedWithoutContentCount = rejectedWithoutContent,
            reason = reason
        )
    }

    private fun inspectCardContent(card: AccessibilityNodeInfo): CardContentInspection {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(card))
        var visited = 0
        var hasVisibleContent = false
        var isLiveStream = false

        try {
            while (queue.isNotEmpty() && visited < MAX_CONTENT_NODES_PER_CARD) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
                        val description = runCatching {
                            node.contentDescription?.toString().orEmpty()
                        }.getOrDefault("")
                        if (text.isNotBlank() || description.isNotBlank()) {
                            hasVisibleContent = true
                        }
                        if (containsLiveRoomMarker(text) || containsLiveRoomMarker(description)) {
                            isLiveStream = true
                            break
                        }

                        val className = runCatching { node.className?.toString().orEmpty() }
                            .getOrDefault("")
                        if (isVisibleMediaNode(className, node)) {
                            hasVisibleContent = true
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= MAX_CONTENT_NODES_PER_CARD) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
        }

        return CardContentInspection(
            isLiveStream = isLiveStream,
            hasVisibleContent = hasVisibleContent
        )
    }

    private fun isVisibleMediaNode(className: String, node: AccessibilityNodeInfo): Boolean {
        val simpleName = className.substringAfterLast('.')
        if (simpleName != "ImageView" && simpleName != "SurfaceView" && simpleName != "TextureView") {
            return false
        }
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        return !bounds.isEmpty
    }

    private fun containsLiveRoomMarker(rawValue: String): Boolean {
        val value = rawValue.lowercase(Locale.ROOT).trim()
        if (value.isBlank()) return false

        val compact = value.replace(Regex("\\s+"), "")
        if (liveRoomMarkers.any { compact.contains(it.lowercase(Locale.ROOT)) }) return true

        // Catch standalone badges such as "直播" or "LIVE" without rejecting
        // ordinary post text that merely discusses livestreaming in general.
        if (compact == "直播" || compact == "live" || compact == "livenow" || compact == "livestream") {
            return true
        }
        val spacedEnglish = value.replace(Regex("\\s+"), " ")
        return spacedEnglish == "live room" || spacedEnglish == "live now" ||
            spacedEnglish == "live stream"
    }
}
