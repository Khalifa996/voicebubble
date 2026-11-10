package io.github.khalifa996.voicebubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Types text into the focused text box of another app, leaving the clipboard alone.
 *
 * In order of preference:
 * 1. Type it the way a keyboard does (Android 13+). Works in almost every app, inserts at
 *    the cursor and keeps the app's undo.
 * 2. Splice it into the box's text at the cursor.
 * 3. Paste it, for the odd app that refuses both. The copy is marked private so keyboards
 *    don't offer it in their clipboard suggestions.
 * 4. With no text box at all, copy it so you can paste it yourself.
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && typeLikeAKeyboard(service, node, text)) {
            return Outcome.TYPED
        }
        if (node == null) {
            copy(service, text, private = false)
            return Outcome.COPIED
        }
        val toInsert = withLeadingSpace(precedingChar(node), text)
        if (setTextAtCursor(node, toInsert)) return Outcome.TYPED
        copy(service, toInsert, private = true)
        if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return Outcome.PASTED
        // Pasting failed too, so leave it on the clipboard where you can see it.
        copy(service, toInsert, private = false)
        return Outcome.COPIED
    }

    /** Android 13+: commit the text through the accessibility input connection, like a keyboard. */
    private fun typeLikeAKeyboard(service: AccessibilityService, node: AccessibilityNodeInfo?, text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val ime = service.inputMethod ?: return false
            val connection = ime.currentInputConnection ?: return false
            val editor = ime.currentInputEditorInfo ?: return false
            if (!ime.currentInputStarted || editor.inputType == InputType.TYPE_NULL) return false
            // Make sure the connection belongs to the box we're typing into, not an old one.
            val nodePackage = node?.packageName?.toString()
            if (nodePackage != null && editor.packageName != nodePackage) return false

            // Selection positions here are relative to the returned snippet of text.
            val before = connection.getSurroundingText(1, 0, 0)?.let { surrounding ->
                surrounding.text.getOrNull(surrounding.selectionStart - 1)
            } ?: node?.let { precedingChar(it) }
            connection.commitText(withLeadingSpace(before, text), 1, null)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Up to [max] characters just before the cursor in the focused box, for context. Never passwords. */
    fun textBeforeCursor(service: AccessibilityService, max: Int): String = try {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.isPassword) {
            ""
        } else {
            val current = currentText(node)
            val cursor = node.textSelectionStart.let { if (it in 0..current.length) it else current.length }
            current.substring(0, cursor).takeLast(max)
        }
    } catch (e: RuntimeException) {
        ""
    }

    /** The text box that has focus now, or the one you started in, if it's still there. */
    private fun pickTarget(service: AccessibilityService, remembered: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        if (remembered != null && remembered.refresh() && remembered.isEditable) return remembered
        return null
    }

    private fun copy(service: AccessibilityService, text: String, private: Boolean) {
        val clip = ClipData.newPlainText("VoiceBubble", text)
        if (private && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        service.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    private fun precedingChar(node: AccessibilityNodeInfo): Char? {
        val current = currentText(node)
        val cursor = node.textSelectionStart
        return if (cursor in 1..current.length) current[cursor - 1] else null
    }

    /** Adds a space when dictating straight after a word, so "hello" + "there" isn't "hellothere". */
    private fun withLeadingSpace(before: Char?, text: String): String {
        if (before == null || before.isWhitespace()) return text
        val first = text.first()
        return if (first.isLetterOrDigit() || first == '£' || first == '(') " $text" else text
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
