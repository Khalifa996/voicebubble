package io.github.khalifa996.voicebubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types text into the focused text box of another app.
 *
 * Types directly first, splicing the words in at the cursor, which leaves the clipboard
 * alone. Only if there's no text box selected, or the app refuses typed text, does it use
 * the clipboard: pasting where it can, otherwise just copying so nothing is lost.
 */
object TextInserter {

    enum class Outcome {
        /** Typed into the box; clipboard untouched. */
        TYPED,
        /** The app only accepted a paste, so it went via the clipboard. */
        PASTED,
        /** No usable text box: the text is on the clipboard for you to paste. */
        COPIED,
    }

    fun insert(service: AccessibilityService, remembered: AccessibilityNodeInfo?, text: String): Outcome {
        if (text.isEmpty()) return Outcome.TYPED
        val node = pickTarget(service, remembered)
        if (node == null) {
            copy(service, text)
            return Outcome.COPIED
        }
        val toInsert = withLeadingSpace(node, text)
        if (setTextAtCursor(node, toInsert)) return Outcome.TYPED
        // A few apps (mostly web pages) ignore typed text but accept a paste.
        copy(service, toInsert)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) Outcome.PASTED else Outcome.COPIED
    }

    /** The text box that has focus now, or the one you started in, if it's still there. */
    private fun pickTarget(service: AccessibilityService, remembered: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        if (remembered != null && remembered.refresh() && remembered.isEditable) return remembered
        return null
    }

    private fun copy(service: AccessibilityService, text: String) {
        service.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("VoiceBubble", text))
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
