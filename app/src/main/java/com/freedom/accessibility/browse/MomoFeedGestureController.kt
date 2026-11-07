package com.freedom.accessibility.browse

import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.accessibility.gesture.GestureTuning
import com.freedom.data.MonitorStore
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns feed-list target discovery, readable list snapshots and scroll gesture planning.
 *
 * This component only inspects nodes and creates immutable gesture plans. It does not
 * dispatch gestures, schedule retries, change browse phases, or handle gesture callbacks.
 * FreedomAccessibilityService remains the owner of those runtime decisions.
 */
internal class MomoFeedGestureController(
    private val context: Context,
    private val pageInspector: MomoPageInspector,
    private val onLog: (String) -> Unit,
    private val config: Config
) {
    internal data class Config(
        val minListWidthRatio: Float,
        val minListHeightRatio: Float,
        val accountBrowsePrefs: String = "freedom_main_ui",
        val keyGestureDurationMs: String = "account_browse_gesture_duration_ms",
        val keyGestureCurveOffset: String = "account_browse_gesture_curve_offset",
        val defaultGestureDurationMs: Long = 620L,
        val minGestureDurationMs: Long = 350L,
        val maxGestureDurationMs: Long = 1_200L,
        val defaultCurveOffsetRatio: Float = 0.012f,
        val maxCurveOffsetRatio: Float = 0.04f,
        val minGestureWidthPx: Int = 220,
        val minGestureHeightPx: Int = 300,
        val gesturePathSteps: Int = 32,
        val enableMicroPerturbation: Boolean = true,
        val microPerturbationAmplitudePx: Float = 1.2f,
        val microPerturbationPathSteps: Int = 100,
        val minComposerWidthRatio: Float = 0.35f,
        val composerBottomRatio: Float = 0.62f,
        val snapshotMaxNodes: Int = 500,
        val snapshotMaxTextNodes: Int = 8,
        val snapshotTextLength: Int = 36,
        val snapshotMaxLogLength: Int = 520,
        val snapshotTopRatio: Float = 0.18f,
        val snapshotBottomRatio: Float = 0.92f
    )

    fun resolveFeedGestureTarget(root: AccessibilityNodeInfo): FeedGestureTarget? {
        val tabInspection = pageInspector.inspectDynamicTabRow(root)
        val tabEvidence = tabInspection.evidence ?: run {
            onLog("无法取得首次滑动区域：${tabInspection.reason}")
            return null
        }
        val listNode = pageInspector.findVisibleFeedContainer(root, tabEvidence.contentTop) ?: run {
            onLog("无法取得首次滑动区域：动态标签下方没有可见列表容器")
            return null
        }
        return try {
            val rawBounds = Rect()
            listNode.getBoundsInScreen(rawBounds)
            val metrics = context.resources.displayMetrics
            val clipped = Rect(
                maxOf(rawBounds.left, 0),
                maxOf(rawBounds.top, tabEvidence.contentTop, 0),
                minOf(rawBounds.right, metrics.widthPixels),
                minOf(rawBounds.bottom, metrics.heightPixels)
            )
            val className = listNode.className?.toString()?.substringAfterLast('.') ?: "Unknown"
            if (clipped.width() < config.minGestureWidthPx ||
                clipped.height() < config.minGestureHeightPx
            ) {
                onLog(
                    "目标列表有效滑动区域过小：class=$className，" +
                        "原始边界=$rawBounds，有效边界=$clipped"
                )
                null
            } else {
                FeedGestureTarget(clipped, className)
            }
        } catch (error: Exception) {
            onLog("读取动态列表屏幕边界失败：${error.javaClass.simpleName}")
            null
        } finally {
            runCatching { listNode.recycle() }
        }
    }

    fun captureVisibleFeedNodeSnapshot(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val lines = ArrayList<String>(config.snapshotMaxTextNodes)
        var visited = 0
        val screenHeight = context.resources.displayMetrics.heightPixels
        try {
            while (queue.isNotEmpty() && visited < config.snapshotMaxNodes &&
                lines.size < config.snapshotMaxTextNodes
            ) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        val text = runCatching { node.text?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString().orEmpty().trim() }
                            .getOrDefault("")
                        val content = sequenceOf(text, description)
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                        if (content.isNotBlank() && bounds.top >= (screenHeight * config.snapshotTopRatio).toInt() &&
                            bounds.top < (screenHeight * config.snapshotBottomRatio).toInt()
                        ) {
                            val nodeClass = node.className?.toString().orEmpty().substringAfterLast('.')
                            lines.add(
                                "$nodeClass:${content.take(config.snapshotTextLength)}@" +
                                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                            )
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= config.snapshotMaxNodes ||
                            lines.size >= config.snapshotMaxTextNodes
                        ) break
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
        return lines.joinToString(" | ").take(config.snapshotMaxLogLength)
    }

    fun loadScrollGestureProfile(): ScrollGestureProfile {
        val prefs = context.getSharedPreferences(config.accountBrowsePrefs, Context.MODE_PRIVATE)
        return ScrollGestureProfile(
            durationMs = prefs.getLong(config.keyGestureDurationMs, config.defaultGestureDurationMs)
                .coerceIn(config.minGestureDurationMs, config.maxGestureDurationMs),
            curveOffsetRatio = (prefs.getFloat(config.keyGestureCurveOffset, config.defaultCurveOffsetRatio))
                .coerceIn(0f, config.maxCurveOffsetRatio),
            microPerturbationFrequencyHz = GestureTuning.currentFrequencyHz(context)
        )
    }

    fun createSmoothScrollPath(
        bounds: Rect,
        profile: ScrollGestureProfile
    ): ScrollGesturePlan? {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val horizontalInset = maxOf(
            (8f * density).toInt(),
            (bounds.width() * 0.04f).toInt()
        ).coerceAtMost(bounds.width() / 4)
        val verticalInset = (8f * density).toInt().coerceAtMost(bounds.height() / 8)

        val safeLeft = bounds.left + horizontalInset
        val safeRight = bounds.right - horizontalInset
        val safeTop = bounds.top + verticalInset
        val safeBottom = bounds.bottom - verticalInset
        val safeWidth = (safeRight - safeLeft).toFloat()
        val safeHeight = (safeBottom - safeTop).toFloat()
        if (safeWidth < 80f || safeHeight < 120f) return null

        fun randomBetween(min: Float, max: Float): Float =
            min + Random.nextFloat() * (max - min)

        // Select a new start/end point from the currently visible list area on every swipe.
        val startX = (safeLeft + safeWidth * randomBetween(0.18f, 0.82f)).toInt()
        val endX = (
            startX + safeWidth * randomBetween(-0.025f, 0.025f)
        ).toInt().coerceIn(safeLeft, safeRight)
        val startY = (safeTop + safeHeight * randomBetween(0.68f, 0.82f)).toInt()
        val endY = (safeTop + safeHeight * randomBetween(0.22f, 0.38f)).toInt()

        val startXf = startX.toFloat()
        val startYf = startY.toFloat()
        val endXf = endX.toFloat()
        val endYf = endY.toFloat()
        val curveLimit = (bounds.width() * profile.curveOffsetRatio)
            .coerceAtMost(bounds.width() * config.maxCurveOffsetRatio)
        val control1X = (startXf + randomBetween(-curveLimit, curveLimit))
            .coerceIn(safeLeft.toFloat(), safeRight.toFloat())
        val control2X = (endXf + randomBetween(-curveLimit, curveLimit))
            .coerceIn(safeLeft.toFloat(), safeRight.toFloat())
        val verticalDistance = endYf - startYf
        val control1Y = startYf + verticalDistance / 3f
        val control2Y = startYf + verticalDistance * 2f / 3f

        val path = Path()
        val useMicroPerturbation = config.enableMicroPerturbation
        val steps = if (useMicroPerturbation) {
            config.microPerturbationPathSteps
        } else {
            config.gesturePathSteps
        }

        for (step in 0..steps) {
            val t = step.toFloat() / steps.toFloat()
            // 2.1: cosine ease-in-out controls progress along the Bezier curve.
            val progress = (0.5 - 0.5 * cos(PI * t.toDouble())).toFloat()
            val inverse = 1f - progress
            val inverseSquared = inverse * inverse
            val progressSquared = progress * progress

            val x = inverseSquared * inverse * startXf +
                3f * inverseSquared * progress * control1X +
                3f * inverse * progressSquared * control2X +
                progressSquared * progress * endXf
            val y = inverseSquared * inverse * startYf +
                3f * inverseSquared * progress * control1Y +
                3f * inverse * progressSquared * control2Y +
                progressSquared * progress * endYf

            var pathX = x
            var pathY = y
            if (useMicroPerturbation && step > 0 && step < steps) {
                // 5.1: calibrated device frequency, 1.2 px offset along the local curve normal.
                val tangentX = 3f * inverseSquared * (control1X - startXf) +
                    6f * inverse * progress * (control2X - control1X) +
                    3f * progressSquared * (endXf - control2X)
                val tangentY = 3f * inverseSquared * (control1Y - startYf) +
                    6f * inverse * progress * (control2Y - control1Y) +
                    3f * progressSquared * (endYf - control2Y)
                val tangentLength = hypot(tangentX.toDouble(), tangentY.toDouble())
                    .toFloat().coerceAtLeast(1f)
                val elapsedSeconds = t * profile.durationMs / 1_000f
                val wave = sin(
                    2.0 * PI * profile.microPerturbationFrequencyHz * elapsedSeconds.toDouble()
                ).toFloat() * config.microPerturbationAmplitudePx
                pathX += (-tangentY / tangentLength) * wave
                pathY += (tangentX / tangentLength) * wave
            }

            if (step == 0) path.moveTo(pathX, pathY) else path.lineTo(pathX, pathY)
        }
        return ScrollGesturePlan(path, startX, startY, endX, endY)
    }

    fun findScrollableFeedContainer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val minimumWidth = (screenWidth * config.minListWidthRatio).toInt()
        val minimumHeight = (screenHeight * config.minListHeightRatio).toInt()
        val minimumComposerWidth = (screenWidth * config.minComposerWidthRatio).toInt()

        var bestNode: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var hasBottomTextEntry = false
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < MonitorStore.MAX_NODES_PER_SCAN) {
                val node = queue.removeFirst()
                visited++

                try {
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }

                    if (node.isVisibleToUser && node.isEditable &&
                        bounds.bottom >= (screenHeight * config.composerBottomRatio).toInt() &&
                        bounds.width() >= minimumComposerWidth
                    ) {
                        hasBottomTextEntry = true
                    }

                    val className = node.className?.toString().orEmpty()
                    val isKnownVerticalList =
                        className.endsWith("RecyclerView") ||
                        className.endsWith("ListView") ||
                        className.endsWith("AbsListView") ||
                        className.endsWith("ScrollView") ||
                        className.endsWith("NestedScrollView") ||
                        className.endsWith("WebView")

                    if (node.isVisibleToUser && node.isScrollable && isKnownVerticalList) {
                        val width = bounds.width()
                        val height = bounds.height()
                        if (width >= minimumWidth && height >= minimumHeight) {
                            val area = width.toLong() * height.toLong()
                            if (area > bestArea) {
                                runCatching { bestNode?.recycle() }
                                bestNode = AccessibilityNodeInfo.obtain(node)
                                bestArea = area
                            }
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= MonitorStore.MAX_NODES_PER_SCAN) break
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

        if (hasBottomTextEntry) {
            runCatching { bestNode?.recycle() }
            return null
        }
        return bestNode
    }
}
