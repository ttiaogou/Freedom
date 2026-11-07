package com.freedom.accessibility.event

import android.view.accessibility.AccessibilityEvent

/**
 * Separates lightweight event logging, page metadata updates and expensive tree scans.
 * A scan is requested only when the window hierarchy is likely to have changed.
 */
internal enum class AccessibilityEventKind(
    val updatesPageMetadata: Boolean,
    val requestsNodeScan: Boolean
) {
    ORDINARY(updatesPageMetadata = false, requestsNodeScan = false),
    PAGE_METADATA(updatesPageMetadata = true, requestsNodeScan = false),
    PAGE_STRUCTURE(updatesPageMetadata = true, requestsNodeScan = true)
}

internal object AccessibilityEventPolicy {

    fun classify(eventType: Int, contentChangeTypes: Int): AccessibilityEventKind =
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> AccessibilityEventKind.PAGE_STRUCTURE

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val structureChanges =
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED

                if ((contentChangeTypes and structureChanges) != 0) {
                    AccessibilityEventKind.PAGE_STRUCTURE
                } else {
                    // Text/description/title changes can update the page hint, but do not
                    // justify another full BFS walk of an unchanged view hierarchy.
                    AccessibilityEventKind.PAGE_METADATA
                }
            }

            else -> AccessibilityEventKind.ORDINARY
        }
}
