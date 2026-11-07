package com.freedom.accessibility.browse

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Reads Momo page structure and returns evidence only.
 *
 * This component does not click, scroll, navigate, schedule work, or mutate the
 * account-browse state machine. The service remains responsible for deciding
 * what action to take from the inspection results.
 */
internal class MomoPageInspector(
    private val context: Context,
    private val dp: (Int) -> Int,
    private val config: Config
) {
    internal data class Config(
        val targetPackage: String,
        val detailActivityClass: String,
        val minDetailNodeCount: Int,
        val homeNavLabels: Set<String>,
        val homeTabLabel: String,
        val homeNavLowerBandRatio: Float,
        val dynamicTabLabels: Set<String>,
        val dynamicTabLabel: String,
        val nearbyTabLabel: String,
        val aiTabLabel: String,
        val dynamicTabUpperBandRatio: Float,
        val dynamicTabRowToleranceDp: Int,
        val dynamicTabContentGapDp: Int,
        val pageFingerprintMaxNodes: Int,
        val fingerprintSizeBucketPx: Int,
        val minListWidthRatio: Float,
        val minListHeightRatio: Float
    )

    fun inspectHomeBottomNav(root: AccessibilityNodeInfo): HomeBottomNavEvidence? {
        val lowerBandTop = (
            context.resources.displayMetrics.heightPixels * config.homeNavLowerBandRatio
        ).toInt()
        val rowTolerance = maxOf(
            dp(config.dynamicTabRowToleranceDp),
            (context.resources.displayMetrics.heightPixels * 0.04f).toInt()
        )
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to false)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes) {
                val (node, selectedInPath) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in config.homeNavLabels -> text
                        description in config.homeNavLabels -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.centerY() >= lowerBandTop
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to selected)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        val homeLabels = labels.filter { it.label == config.homeTabLabel }
        for (home in homeLabels) {
            val peerLabels = labels.filter {
                it.label != config.homeTabLabel &&
                    kotlin.math.abs(it.bounds.centerY() - home.bounds.centerY()) <= rowTolerance
            }
            if (peerLabels.isEmpty()) continue
            val selectedLabels = (listOf(home) + peerLabels).filter { it.selected }.map { it.label }.distinct()
            val selectionKnown = selectedLabels.size == 1
            val homeSelected = selectionKnown && selectedLabels.single() == config.homeTabLabel
            return HomeBottomNavEvidence(
                homeBounds = Rect(home.bounds),
                homeSelected = homeSelected,
                selectionKnown = selectionKnown,
                diagnostic = if (selectionKnown) {
                    "底部导航选中项=${selectedLabels.single()}"
                } else {
                    "底部导航存在，但选中状态未能唯一确认"
                }
            )
        }
        return null
    }

    fun isMomoDynamicFeedPage(root: AccessibilityNodeInfo): Boolean =
        inspectMomoFeedReadiness(root).fingerprint != null

    /**
     * Momo's target feed is the top-level Home page's inner "动态" tab.
     * The bottom navigation's "首页" is a different navigation level and must not
     * be used as the feed-tab marker. We identify the top tab row by its visible
     * labels (动态 / 附近 / AI小陌), then validate a visible list below that row.
     */
    fun inspectMomoFeedReadiness(root: AccessibilityNodeInfo): FeedReadinessInspection {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (packageName != config.targetPackage) {
            return FeedReadinessInspection(null, "当前窗口不是陌陌：$packageName")
        }

        val tabInspection = inspectDynamicTabRow(root)
        val tabEvidence = tabInspection.evidence
            ?: return FeedReadinessInspection(null, tabInspection.reason)
        if (tabEvidence.selectionKnown && !tabEvidence.dynamicSelected) {
            return FeedReadinessInspection(null, "首页顶部当前选中项不是‘动态’；${tabEvidence.diagnostic}")
        }

        val listNode = findVisibleFeedContainer(root, tabEvidence.contentTop)
            ?: return FeedReadinessInspection(null, "已确认首页顶部动态标签行，但下方没有找到足够大的可见列表容器")

        return try {
            val bounds = Rect()
            listNode.getBoundsInScreen(bounds)
            val metrics = context.resources.displayMetrics
            val minWidth = (metrics.widthPixels * config.minListWidthRatio).toInt()
            val visibleContentTop = maxOf(bounds.top, tabEvidence.contentTop)
            val visibleContentHeight = bounds.bottom - visibleContentTop
            val minHeight = (metrics.heightPixels * config.minListHeightRatio).toInt()

            if (!listNode.isVisibleToUser) {
                return FeedReadinessInspection(null, "列表容器存在，但当前不可见")
            }
            if (bounds.width() < minWidth || visibleContentHeight < minHeight) {
                return FeedReadinessInspection(
                    null,
                    "列表有效区域尺寸不足：宽=${bounds.width()}，有效高=$visibleContentHeight"
                )
            }

            val contentBounds = Rect(bounds)
            contentBounds.top = visibleContentTop
            var visibleChildren = 0
            val childCount = runCatching { listNode.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { listNode.getChild(index) }.getOrNull() ?: continue
                try {
                    val childBounds = Rect()
                    child.getBoundsInScreen(childBounds)
                    if (child.isVisibleToUser && childBounds.width() > 0 && childBounds.height() > 0 &&
                        Rect.intersects(contentBounds, childBounds)
                    ) {
                        visibleChildren++
                    }
                } finally {
                    runCatching { child.recycle() }
                }
            }
            if (visibleChildren == 0) {
                return FeedReadinessInspection(null, "列表容器和尺寸有效，但当前没有可见列表项目")
            }

            val className = listNode.className?.toString().orEmpty().substringAfterLast('.')
            val signature = listOf(
                className,
                bounds.left / config.fingerprintSizeBucketPx,
                visibleContentTop / config.fingerprintSizeBucketPx,
                bounds.width() / config.fingerprintSizeBucketPx,
                visibleContentHeight / config.fingerprintSizeBucketPx,
                when {
                    visibleChildren <= 1 -> 1
                    visibleChildren <= 3 -> 2
                    visibleChildren <= 6 -> 3
                    else -> 4
                }
            ).joinToString(":")
            FeedReadinessInspection(
                PageFingerprint(signature = signature, nodeCount = visibleChildren),
                "${tabEvidence.diagnostic}；列表=$className，当前可见项目=$visibleChildren"
            )
        } finally {
            runCatching { listNode.recycle() }
        }
    }

    /**
     * Locate the upper horizontal tab row. Resource IDs are deliberately not required:
     * Momo versions may expose different IDs or omit IDs from accessibility nodes.
     * If another tab is selected, its state and the Dynamic label bounds are both retained
     * so the caller can actively click Dynamic. If selection semantics are unavailable,
     * the same-row navigation labels provide a bounded structural fallback.
     */
    fun inspectDynamicTabRow(root: AccessibilityNodeInfo): DynamicTabInspection {
        val metrics = context.resources.displayMetrics
        val upperBandBottom = (metrics.heightPixels * config.dynamicTabUpperBandRatio).toInt()
        val rowTolerance = maxOf(
            dp(config.dynamicTabRowToleranceDp),
            (metrics.heightPixels * 0.04f).toInt()
        )
        val labels = ArrayList<TabLabelEvidence>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to false)
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes) {
                val (node, selectedInPath) = queue.removeFirst()
                visited++
                try {
                    val selected = selectedInPath || node.isSelected || node.isChecked
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                    val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                        .getOrDefault("")
                    val label = when {
                        text in config.dynamicTabLabels -> text
                        description in config.dynamicTabLabels -> description
                        else -> ""
                    }
                    if (label.isNotEmpty() && node.isVisibleToUser && bounds.width() > 0 &&
                        bounds.height() > 0 && bounds.top >= 0 && bounds.centerY() <= upperBandBottom
                    ) {
                        labels.add(TabLabelEvidence(label, Rect(bounds), selected))
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to selected)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        val dynamicLabels = labels.filter { it.label == config.dynamicTabLabel }
        val nearbyLabels = labels.filter { it.label == config.nearbyTabLabel }
        val aiLabels = labels.filter { it.label == config.aiTabLabel }
        if (dynamicLabels.isEmpty()) {
            return DynamicTabInspection(null, "屏幕顶部区域没有识别到‘动态’标签")
        }

        var bestRow: List<TabLabelEvidence>? = null
        var bestScore = -1
        for (dynamic in dynamicLabels) {
            val sameRowNearby = nearbyLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }
            val sameRowAi = aiLabels.filter {
                kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) <= rowTolerance &&
                    it.bounds.centerX() > dynamic.bounds.centerX()
            }.minByOrNull { kotlin.math.abs(it.bounds.centerY() - dynamic.bounds.centerY()) }

            // The photo shows these three labels in one horizontal row. Requiring at least
            // one neighbor avoids matching unrelated "动态" text elsewhere in the page.
            if (sameRowNearby != null || sameRowAi != null) {
                val row = listOfNotNull(dynamic, sameRowNearby, sameRowAi)
                val score = (if (sameRowNearby != null) 1 else 0) + (if (sameRowAi != null) 1 else 0)
                if (score > bestScore) {
                    bestRow = row
                    bestScore = score
                }
            }
        }

        val row = bestRow ?: return DynamicTabInspection(
            null,
            "识别到顶部‘动态’，但未找到同一行的‘附近’或‘AI小陌’标签，无法确认是首页顶部导航"
        )
        val dynamic = row.first { it.label == config.dynamicTabLabel }
        val selectedLabels = row.filter { it.selected }.map { it.label }.distinct()
        val selectionKnown = selectedLabels.size == 1
        val dynamicSelected = selectionKnown && selectedLabels.single() == config.dynamicTabLabel

        val rowBottom = row.maxOf { it.bounds.bottom }
        val contentTop = rowBottom + dp(config.dynamicTabContentGapDp)
        val selectionDescription = when {
            dynamicSelected -> "顶部‘动态’标签已选中"
            selectionKnown -> "顶部导航当前选中项为‘${selectedLabels.single()}’；可以主动切换到‘动态’"
            selectedLabels.size > 1 -> "Tab选中状态存在祖先节点歧义，按顶部标签行结构识别"
            else -> "无障碍节点未暴露Tab选中状态，按‘动态/附近/AI小陌’同排结构识别"
        }
        val neighborDescription = if (bestScore == 2) "附近与AI小陌同排" else "附近或AI小陌同排"
        return DynamicTabInspection(
            DynamicTabRowEvidence(
                contentTop = contentTop,
                diagnostic = "$selectionDescription，$neighborDescription",
                dynamicBounds = Rect(dynamic.bounds),
                dynamicSelected = dynamicSelected,
                selectionKnown = selectionKnown
            ),
            ""
        )
    }

    /** Finds a visible list-like container below the confirmed top Dynamic-tab row. */
    fun findVisibleFeedContainer(
        root: AccessibilityNodeInfo,
        contentTop: Int
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val metrics = context.resources.displayMetrics
        val minWidth = (metrics.widthPixels * config.minListWidthRatio).toInt()
        val minHeight = (metrics.heightPixels * config.minListHeightRatio).toInt()
        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes) {
                val node = queue.removeFirst()
                visited++
                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val visibleTop = maxOf(bounds.top, contentTop)
                    val visibleHeight = bounds.bottom - visibleTop
                    val className = node.className?.toString().orEmpty()
                    val isListLike = className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") || className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") || className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")
                    if (node.isVisibleToUser && isListLike && bounds.width() >= minWidth &&
                        visibleHeight >= minHeight
                    ) {
                        val area = bounds.width().toLong() * visibleHeight.toLong()
                        if (area > bestArea) {
                            runCatching { bestNode?.recycle() }
                            bestNode = AccessibilityNodeInfo.obtain(node)
                            bestArea = area
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) break
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
        return bestNode
    }

    fun pageFingerprint(root: AccessibilityNodeInfo): PageFingerprint {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)
        val structure = ArrayList<String>()
        var visibleCount = 0
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < config.pageFingerprintMaxNodes) {
                val (node, depth) = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        visibleCount++
                        val className = node.className?.toString().orEmpty().substringAfterLast('.')
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        structure.add(
                            "$depth:$className:$viewId:${node.isClickable}:${node.isScrollable}:" +
                                "${bounds.width() / config.fingerprintSizeBucketPx}:${bounds.height() / config.fingerprintSizeBucketPx}"
                        )
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.pageFingerprintMaxNodes) break
                        val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                        queue.add(child to depth + 1)
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }
        return PageFingerprint(
            signature = "$visibleCount:${structure.joinToString("|").hashCode()}",
            nodeCount = visibleCount
        )
    }

    /**
     * Confirms the known Momo detail Activity and verifies that the current window is not the feed.
     * The current/expected Activity values are supplied by the service because they belong to its
     * live navigation session, not to this stateless inspector.
     */
    fun inspectCurrentDynamicDetailIdentity(
        root: AccessibilityNodeInfo,
        expectedActivity: String,
        currentActivity: String
    ): DetailPageIdentityInspection {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }
            .getOrDefault("")
        val fingerprint = pageFingerprint(root)
        val feedPage = isMomoDynamicFeedPage(root)
        val identityConfirmed = packageName == config.targetPackage &&
            !feedPage &&
            expectedActivity == config.detailActivityClass &&
            currentActivity == expectedActivity &&
            fingerprint.nodeCount >= config.minDetailNodeCount

        val reason = when {
            packageName != config.targetPackage -> "当前窗口包名不是陌陌：$packageName"
            feedPage -> "当前页面已经识别为动态列表"
            currentActivity != expectedActivity ->
                "Activity 不匹配：当前=${currentActivity.ifBlank { "未知" }}，预期=$expectedActivity"
            fingerprint.nodeCount < config.minDetailNodeCount -> "详情页面可见节点不足：${fingerprint.nodeCount}"
            else -> "Activity=$currentActivity；详情节点=${fingerprint.nodeCount}"
        }
        return DetailPageIdentityInspection(feedPage, identityConfirmed, fingerprint, reason)
    }

}
