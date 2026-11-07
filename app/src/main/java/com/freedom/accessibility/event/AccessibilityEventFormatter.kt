package com.freedom.accessibility.event

import android.view.accessibility.AccessibilityEvent

/** Stable mapping from Android accessibility event types to Freedom's user-facing labels. */
internal object AccessibilityEventFormatter {
    fun rawEventName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "TYPE_VIEW_SELECTED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "TYPE_VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "TYPE_NOTIFICATION_STATE_CHANGED"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "TYPE_VIEW_HOVER_ENTER"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "TYPE_VIEW_HOVER_EXIT"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "TYPE_TOUCH_EXPLORATION_GESTURE_START"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "TYPE_TOUCH_EXPLORATION_GESTURE_END"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "TYPE_VIEW_TEXT_SELECTION_CHANGED"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "TYPE_ANNOUNCEMENT"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "TYPE_VIEW_ACCESSIBILITY_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "TYPE_GESTURE_DETECTION_START"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "TYPE_GESTURE_DETECTION_END"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TYPE_TOUCH_INTERACTION_START"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TYPE_TOUCH_INTERACTION_END"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "TYPE_VIEW_CONTEXT_CLICKED"
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "TYPE_ASSIST_READING_CONTEXT"
        else -> "ACCESSIBILITY_EVENT_$type"
    }

    private fun eventLabel(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "控件被点击"
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "控件被长按"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "控件被选中"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "控件获得焦点"
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "控件文字发生变化"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "页面或窗口发生切换"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "窗口内容发生变化"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "页面发生滚动"
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "文字选区发生变化"
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> "文字按粒度移动"
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "指针进入控件"
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "指针离开控件"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "触摸探索手势开始"
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "触摸探索手势结束"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "手势检测开始"
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "手势检测结束"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "触摸交互开始"
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "触摸交互结束"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> "控件获得无障碍焦点"
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> "控件失去无障碍焦点"
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "控件上下文点击"
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> "系统播报内容"
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "辅助阅读上下文变化"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "系统窗口发生变化"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "通知状态发生变化"
        else -> "无障碍事件"
    }

    /** Convert technical accessibility events into a plain-language event summary. */
    fun friendlyEventDescription(
        eventType: Int,
        className: String,
        content: String,
        description: String
    ): String {
        val rawClass = className.substringAfterLast('.')
        val area = when (rawClass) {
            "RecyclerView" -> "内容列表"
            "ScrollView", "HorizontalScrollView" -> "可滚动区域"
            "EditText" -> "文字输入框"
            "TextView" -> "文字区域"
            "Button", "ImageButton" -> "按钮区域"
            "ImageView" -> "图片区域"
            "WebView" -> "网页内容区域"
            "ViewPager", "ViewPager2" -> "分页区域"
            "CheckBox", "RadioButton", "Switch" -> "选项控件"
            "" -> "当前界面"
            else -> "界面控件"
        }

        val title: String
        val explanation: String
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                title = "页面内容更新"
                explanation = "这个区域的内容或结构发生了变化，可能是列表刷新、内容加载或项目更新。"
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                title = if (rawClass == "RecyclerView") "列表滚动" else "页面滚动"
                explanation = if (rawClass == "RecyclerView") {
                    "检测到内容列表的滚动位置或可见项目发生变化。"
                } else {
                    "检测到可滚动区域的位置或可见内容发生变化。"
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                title = "控件点击"
                explanation = "系统报告这个界面控件发生了一次点击事件。"
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                title = "控件长按"
                explanation = "系统报告这个界面控件发生了一次长按事件。"
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                title = "文字内容变化"
                explanation = "一个文字控件中的内容发生了变化。"
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                title = "页面或窗口切换"
                explanation = "当前应用的窗口或页面状态发生了变化。"
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                title = "选项状态变化"
                explanation = "一个界面控件报告了选中状态变化。"
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                title = "控件获得焦点"
                explanation = "输入焦点或键盘焦点移动到了这个控件。"
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                title = "文字选区变化"
                explanation = "文字控件中的选中范围发生了变化。"
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                title = "通知内容变化"
                explanation = "系统报告目标应用的通知状态发生了变化。"
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                title = "应用窗口变化"
                explanation = "系统报告当前应用相关窗口发生了变化。"
            }
            else -> {
                title = eventLabel(eventType)
                explanation = "系统检测到一个界面辅助事件；详细类型可在技术详情中查看。"
            }
        }

        val relatedText = sequenceOf(content, description)
            .firstOrNull { it.isNotBlank() }
            ?.take(100)

        return buildString {
            append(title)
            append("\n操作区域：")
            append(area)
            append("\n说明：")
            append(explanation)
            if (!relatedText.isNullOrBlank()) {
                append("\n相关文字：")
                append(relatedText)
            }
        }
    }
}
