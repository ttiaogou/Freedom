package com.freedom.accessibility.browse

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

/**
 * Selects only a visible Momo card whose accessibility tree has a recognizable
 * social-card structure. Live rooms, advertisements and unknown card types are
 * rejected conservatively. The caller owns and must recycle SelectionResult.node.
 */
object MomoDynamicCardSelector {

    data class SelectionResult(
        val node: AccessibilityNodeInfo?,
        val inspectedCardCount: Int,
        val skippedLiveStreamCount: Int,
        val skippedAdvertisementCount: Int,
        val skippedUnknownCount: Int,
        val reason: String
    )

    private data class CardContentInspection(
        val isLiveStream: Boolean,
        val isAdvertisement: Boolean,
        val hasKnownNormalStructure: Boolean
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

    // These are positive UI signals observed on normal Momo social cards.
    // A generic text/image payload alone is not enough to authorize a click.
    private val socialActionMarkers = listOf(
        "打招呼",
        "评论",
        "点赞",
        "回复",
        "分享",
        "查看全文",
        "展开全文",
        "私信"
    )

    private val genericTextMarkers = setOf(
        "打招呼", "评论", "点赞", "赞", "回复", "分享", "查看全文", "展开全文",
        "私信", "更多", "收起", "展开", "全部", "图片", "视频", "动态"
    )

    private const val MAX_CONTENT_NODES_PER_CARD = 180
    private const val MAX_FEED_MARKER_NODES = 700

    /** Returns an owned node copy; the caller must recycle [SelectionResult.node]. */
    fun select(
        scrollNode: AccessibilityNodeInfo,
        minWidthPx: Int,
        minHeightPx: Int
    ): SelectionResult {
        val listBounds = Rect()
        runCatching { scrollNode.getBoundsInScreen(listBounds) }
        if (listBounds.isEmpty) {
            return SelectionResult(null, 0, 0, 0, 0, "动态列表边界不可用")
        }

        // Some app versions expose a small ad badge as a sibling of the clickable
        // card container. Collect visible ad-label bounds from the list as a second
        // independent signal, then associate only labels geometrically inside a card.
        val adBadgeBounds = collectAdvertisementBadgeBounds(scrollNode, listBounds)

        val childCount = runCatching { scrollNode.childCount }.getOrDefault(0)
        var inspected = 0
        var skippedLive = 0
        var skippedAds = 0
        var skippedUnknown = 0
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

                val hasNearbyAdBadge = adBadgeBounds.any { badge ->
                    val expandedCard = Rect(bounds).apply { inset(-12, -12) }
                    Rect.intersects(expandedCard, badge)
                }
                if (content.isAdvertisement || hasNearbyAdBadge) {
                    skippedAds++
                    continue
                }

                if (!content.hasKnownNormalStructure) {
                    skippedUnknown++
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
            bestNode != null -> "已找到具有可识别社交卡片结构、且未命中广告或直播标记的候选动态"
            inspected == 0 -> "列表中没有满足尺寸、可见性和可点击条件的完整卡片"
            skippedAds + skippedLive + skippedUnknown == inspected ->
                "所有候选均为广告、直播或无法确认类型的卡片，已全部跳过"
            else -> "没有候选同时满足普通社交卡片结构和安全点击条件，未执行点击"
        }

        return SelectionResult(
            node = bestNode,
            inspectedCardCount = inspected,
            skippedLiveStreamCount = skippedLive,
            skippedAdvertisementCount = skippedAds,
            skippedUnknownCount = skippedUnknown,
            reason = reason
        )
    }

    private fun inspectCardContent(card: AccessibilityNodeInfo): CardContentInspection {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(card))
        var visited = 0
        var hasVisibleContentText = false
        var hasVisibleMedia = false
        var hasSocialAction = false
        var isLiveStream = false
        var isAdvertisement = false

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

                        if (containsAdvertisementMarker(text) || containsAdvertisementMarker(description)) {
                            isAdvertisement = true
                        }
                        if (containsLiveRoomMarker(text) || containsLiveRoomMarker(description)) {
                            isLiveStream = true
                        }

                        if (text.isNotBlank() || description.isNotBlank()) {
                            if (isSocialActionText(text) || isSocialActionText(description)) {
                                hasSocialAction = true
                            }
                            if (isMeaningfulCardText(text) || isMeaningfulCardText(description)) {
                                hasVisibleContentText = true
                            }
                        }

                        val className = runCatching { node.className?.toString().orEmpty() }
                            .getOrDefault("")
                        if (isVisibleMediaNode(className, node)) {
                            hasVisibleMedia = true
                        }
                    }

                    if (isAdvertisement || isLiveStream) break

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

        // Require both an interaction control and actual user/media content.
        // A large clickable container with arbitrary text or an image is unknown.
        val hasKnownNormalStructure = hasSocialAction &&
            (hasVisibleContentText || hasVisibleMedia)

        return CardContentInspection(
            isLiveStream = isLiveStream,
            isAdvertisement = isAdvertisement,
            hasKnownNormalStructure = hasKnownNormalStructure
        )
    }

    private fun collectAdvertisementBadgeBounds(
        scrollNode: AccessibilityNodeInfo,
        listBounds: Rect
    ): List<Rect> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(scrollNode))
        val badges = ArrayList<Rect>()
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_FEED_MARKER_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
                        val description = runCatching {
                            node.contentDescription?.toString().orEmpty()
                        }.getOrDefault("")
                        if (containsAdvertisementMarker(text) || containsAdvertisementMarker(description)) {
                            val bounds = Rect()
                            runCatching { node.getBoundsInScreen(bounds) }
                            val smallBadge = !bounds.isEmpty &&
                                bounds.width() <= (listBounds.width() * 0.78f).toInt() &&
                                bounds.height() <= (listBounds.height() * 0.34f).toInt() &&
                                Rect.intersects(listBounds, bounds)
                            if (smallBadge) badges.add(bounds)
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= MAX_FEED_MARKER_NODES) break
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
        return badges
    }

    private fun isSocialActionText(rawValue: String): Boolean {
        val value = rawValue.lowercase(Locale.ROOT).replace(Regex("\\s+"), "").trim()
        if (value.isBlank()) return false
        return socialActionMarkers.any { value.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun isMeaningfulCardText(rawValue: String): Boolean {
        val value = rawValue.trim()
        if (value.length < 2) return false
        val compact = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        if (compact.isBlank() || compact.all { it.isDigit() }) return false
        if (genericTextMarkers.any { compact == it.lowercase(Locale.ROOT) }) return false
        if (containsAdvertisementMarker(value) || containsLiveRoomMarker(value)) return false
        return true
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

    private fun containsAdvertisementMarker(rawValue: String): Boolean {
        val value = rawValue.lowercase(Locale.ROOT).trim()
        if (value.isBlank()) return false
        val compact = value.replace(Regex("\\s+"), "")
        val chineseMarkers = listOf(
            "广告", "推广", "赞助", "商业合作", "品牌合作", "广告推广", "推广内容", "广告位"
        )
        if (chineseMarkers.any { compact.contains(it) }) return true
        return Regex("\\b(ad|ads|advertisement|sponsored|promoted)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
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
