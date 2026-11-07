package com.freedom.accessibility.browse

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Inspects Momo modal and popup windows and returns evidence for the caller.
 *
 * This component never dismisses, clicks, schedules retries, or changes browse state.
 * Retry policy and safe-action decisions remain owned by FreedomAccessibilityService.
 */
internal class MomoPopupInspector(
    private val context: Context,
    private val maxScanNodes: Int,
    private val findClickableAncestor: (AccessibilityNodeInfo, Rect) -> AccessibilityNodeInfo?
) {
    fun inspect(root: AccessibilityNodeInfo): PopupInspection? {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < maxScanNodes) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val className = node.className?.toString().orEmpty()
                        val simpleClass = className.substringAfterLast('.')
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        val dismissable = runCatching { node.isDismissable }.getOrDefault(false)
                        val dialogClass = simpleClass.contains("Dialog", ignoreCase = true) ||
                            simpleClass.contains("Popup", ignoreCase = true) ||
                            simpleClass.contains("BottomSheet", ignoreCase = true)
                        val centeredOverlay = bounds.width() >= (screenWidth * 0.55f).toInt() &&
                            bounds.width() < (screenWidth * 0.99f).toInt() &&
                            bounds.height() >= (screenHeight * 0.16f).toInt() &&
                            bounds.height() < (screenHeight * 0.88f).toInt() &&
                            kotlin.math.abs(bounds.centerX() - screenWidth / 2) <= (screenWidth * 0.20f).toInt() &&
                            bounds.centerY() in (screenHeight * 0.20f).toInt()..(screenHeight * 0.82f).toInt()
                        val score = when {
                            dismissable -> 100
                            dialogClass -> 90
                            centeredOverlay -> 20
                            else -> 0
                        }
                        if (score > 0 && candidates.size < 12) {
                            candidates.add(AccessibilityNodeInfo.obtain(node) to score)
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= maxScanNodes) break
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

        val ordered = candidates.sortedByDescending { it.second }
        for ((container, score) in ordered) {
            val texts = collectPopupTexts(container)
            val joinedText = texts.joinToString(" ").take(360)
            val closeTarget = findPopupCloseTarget(container)
            val popupCue = texts.any { text ->
                POPUP_CONTENT_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
            }
            val hasModalSemantics = score >= 90
            if (!hasModalSemantics && (!popupCue || closeTarget == null)) {
                runCatching { container.recycle() }
                runCatching { closeTarget?.recycle() }
                continue
            }

            val critical = texts.any { text ->
                POPUP_CRITICAL_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
            }
            val dismissable = runCatching { container.isDismissable }.getOrDefault(false)
            val bounds = Rect()
            runCatching { container.getBoundsInScreen(bounds) }
            val description = joinedText.ifBlank {
                "${container.className?.toString()?.substringAfterLast('.') ?: "未知弹窗"}，区域=${bounds.toShortString()}"
            }
            val signature = listOf(
                container.className?.toString().orEmpty(),
                bounds.toShortString(),
                texts.take(5).joinToString("|")
            ).joinToString(":").hashCode().toString()
            for ((other, _) in ordered) {
                if (other !== container) runCatching { other.recycle() }
            }
            return PopupInspection(container, closeTarget, dismissable, critical, signature, description)
        }

        for ((container, _) in ordered) runCatching { container.recycle() }
        return null
    }

    private fun collectPopupTexts(container: AccessibilityNodeInfo): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(container))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 180) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                            .getOrDefault("")
                        for (value in listOf(text, description)) {
                            if (value.isNotBlank() && value !in result && result.size < 24) {
                                result.add(value.take(100))
                            }
                        }
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= 180) break
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
        return result
    }

    private fun findPopupCloseTarget(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(container))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 180) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                            .getOrDefault("")
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                        val label = sequenceOf(text, description).firstOrNull { it.isNotBlank() }.orEmpty()
                        val safeLabel = POPUP_SAFE_CLOSE_LABELS.any { label.equals(it, ignoreCase = true) }
                        val safeId = viewId.substringAfterLast('/').let { id ->
                            id.contains("close", ignoreCase = true) ||
                                id.contains("dismiss", ignoreCase = true) ||
                                id.contains("cancel", ignoreCase = true)
                        }
                        val dangerousLabel = POPUP_CRITICAL_CUES.any { label.contains(it, ignoreCase = true) }
                        if ((safeLabel || safeId) && !dangerousLabel) {
                            val bounds = Rect()
                            runCatching { node.getBoundsInScreen(bounds) }
                            val target = findClickableAncestor(node, bounds)
                            if (target != null) return target
                        }
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= 180) break
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

    fun recycle(popup: PopupInspection) {
        runCatching { popup.container?.recycle() }
        runCatching { popup.closeTarget?.recycle() }
    }

    private companion object {
        private val POPUP_SAFE_CLOSE_LABELS = setOf(
            "关闭", "关闭弹窗", "关闭广告", "取消", "暂不", "暂不升级", "稍后", "稍后再说",
            "以后再说", "知道了", "我知道了", "×", "✕", "x", "X", "Close", "close", "Dismiss", "dismiss"
        )
        private val POPUP_CRITICAL_CUES = setOf(
            "权限", "授权", "允许", "同意", "隐私", "协议", "支付", "充值", "购买",
            "删除", "注销", "退出登录", "身份验证", "安全验证", "实名认证", "确认提交",
            "确定要", "是否删除", "是否退出", "登录", "绑定手机号", "开启定位", "开启相机"
        )
        private val POPUP_CONTENT_CUES = setOf(
            "温馨提示", "系统提示", "公告", "新版本", "版本升级", "弹窗"
        )
    }

}
