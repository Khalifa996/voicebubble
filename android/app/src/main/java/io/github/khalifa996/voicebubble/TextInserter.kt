package io.github.khalifa996.voicebubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types text into the focused field of another app.
 *
 * Paste first: it inserts at the cursor, keeps the app's formatting and undo, and works
 * in browsers and web views. If the app ignores paste, fall back to rewriting the field's
 * text with the new words spliced in at the cursor. Either way the text stays on the
 * clipboard, so nothing is ever lost.
 */
object TextInserter {

    /** Returns false when the text could only be copied to the clipboard. */
    fun insert(service: AccessibilityService, remembered: AccessibilityNodeInfo?, text: String): Boolean {
        if (text.isEmpty()) return true
        val node = pickTarget(service, remembered)
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        val toInsert = if (node != null) withLeadingSpace(node, text) else text
        clipboard?.setPrimaryClip(ClipData.newPlainText("VoiceBubble", toInsert))
        if (node == null) return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return setTextAtCursor(node, toInsert)
    }

    private fun pickTarget(service: AccessibilityService, remembered: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        if (remembered != null && remembered.refresh() && remembered.isEditable) return remembered
        return focused
    }

    /** Adds a space when dictating straight after a word, so "hello" + "there" isn't "hellothere". */
    private fun withLeadingSpace(node: AccessibilityNodeInfo, text: String): String {
        val current = currentText(node)
        val cursor = node.textSelectionStart
        if (cursor <= 0 || cursor > current.length) return text
        val before = current[cursor - 1]
        val first = text.first()
        return if (!before.isWhitespace() && (first.isLetterOrDigit() || first == '£' || first == '(')) " $text" else text
    }

    private fun setTextAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = currentText(node)
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        if (start > end) {
            val swap = start
            start = end
            end = swap
        }
        val updated = current.substring(0, start) + text + current.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) return false
        val cursor = start + text.length
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        return true
    }

    private fun currentText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
}
