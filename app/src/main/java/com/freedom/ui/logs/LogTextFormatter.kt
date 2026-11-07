package com.freedom.ui.logs

/** Formats both current structured log lines and legacy v1 records. */
internal object LogTextFormatter {
    private const val LOG_DETAIL_SEPARATOR = "\u001F"

    fun formatLogLines(
        lines: List<String>,
        includeTechnicalDetails: Boolean
    ): String = lines.joinToString("\n") { line ->
        formatLogLine(line, includeTechnicalDetails)
    }

    private fun formatLogLine(line: String, includeTechnicalDetails: Boolean): String {
        val separatorIndex = line.indexOf(LOG_DETAIL_SEPARATOR)
        if (separatorIndex >= 0) {
            val summary = line.substring(0, separatorIndex).trimEnd()
            val technical = line.substring(separatorIndex + 1).trim()
            return if (includeTechnicalDetails && technical.isNotBlank()) {
                "$summary\n  技术详情：$technical"
            } else {
                summary
            }
        }
        return if (includeTechnicalDetails) line else formatLegacyEventLine(line)
    }

    /** Convert previously stored v1 event strings too, so old history remains readable after upgrade. */
    private fun formatLegacyEventLine(line: String): String {
        val type = Regex("\\[(TYPE_[A-Z0-9_]+)\\]").find(line)?.groupValues?.get(1)
            ?: return line
        val className = Regex("原始类名=([^；]+)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val simpleClass = className.substringAfterLast('.')
        val area = when (simpleClass) {
            "RecyclerView" -> "内容列表"
            "ScrollView", "HorizontalScrollView" -> "可滚动区域"
            "EditText" -> "文字输入框"
            "TextView" -> "文字区域"
            "Button", "ImageButton" -> "按钮区域"
            "ImageView" -> "图片区域"
            "WebView" -> "网页内容区域"
            "ViewPager", "ViewPager2" -> "分页区域"
            "CheckBox", "RadioButton", "Switch" -> "选项控件"
            else -> "界面区域"
        }
        val title = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "页面内容更新"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") "列表滚动" else "页面滚动"
            "TYPE_VIEW_CLICKED" -> "控件点击"
            "TYPE_VIEW_LONG_CLICKED" -> "控件长按"
            "TYPE_VIEW_TEXT_CHANGED" -> "文字内容变化"
            "TYPE_WINDOW_STATE_CHANGED" -> "页面或窗口切换"
            "TYPE_VIEW_SELECTED" -> "选项状态变化"
            "TYPE_VIEW_FOCUSED" -> "控件获得焦点"
            "TYPE_VIEW_TEXT_SELECTION_CHANGED" -> "文字选区变化"
            "TYPE_NOTIFICATION_STATE_CHANGED" -> "通知内容变化"
            "TYPE_WINDOWS_CHANGED" -> "应用窗口变化"
            else -> "界面事件"
        }
        val explanation = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "这个区域的内容或结构发生了变化，可能是列表刷新、内容加载或项目更新。"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") {
                "检测到内容列表的滚动位置或可见项目发生变化。"
            } else {
                "检测到可滚动区域的位置或可见内容发生变化。"
            }
            "TYPE_VIEW_CLICKED" -> "系统报告这个界面控件发生了一次点击事件。"
            "TYPE_VIEW_LONG_CLICKED" -> "系统报告这个界面控件发生了一次长按事件。"
            "TYPE_VIEW_TEXT_CHANGED" -> "一个文字控件中的内容发生了变化。"
            "TYPE_WINDOW_STATE_CHANGED" -> "当前应用的窗口或页面状态发生了变化。"
            else -> "系统检测到界面状态变化；详细类型可在技术详情中查看。"
        }
        val time = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}").find(line)?.value.orEmpty()
        val relatedText = Regex("(?:内容|描述)=([^；]+)").find(line)?.groupValues?.get(1)?.trim()
        return buildString {
            if (time.isNotBlank()) append(time).append("  ")
            append(title)
            append("\n操作区域：").append(area)
            append("\n说明：").append(explanation)
            if (!relatedText.isNullOrBlank()) {
                append("\n相关文字：").append(relatedText.take(100))
            }
        }
    }
}
