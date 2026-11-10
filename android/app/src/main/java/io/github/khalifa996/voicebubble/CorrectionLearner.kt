package io.github.khalifa996.voicebubble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * After a dictation, keeps an eye on the box it typed into for up to a minute, to see what
 * you corrected. It only ever reads that one box, never password boxes, and nothing leaves
 * the phone.
 *
 * It keeps the last version of your text that still looks like the dictation. When the box
 * moves on (you send the message, clear it, or start another dictation) or the minute is
 * up, it compares that with what was typed and learns from the difference.
 */
class CorrectionLearner(
    private val prefs: Prefs,
    private val onLearned: (List<String>) -> Unit,
) {

    private class Watch(
        val node: AccessibilityNodeInfo,
        val appPackage: String?,
        val inserted: String,
        val before: String,
        val after: String,
        val startedAt: Long,
    ) {
        var latest: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watch: Watch? = null

    private val poll = object : Runnable {
        override fun run() {
            val current = watch ?: return
            val alive = try {
                current.node.refresh()
            } catch (e: RuntimeException) {
                false
            }
            if (!alive) {
                finish()
                return
            }
            val text = try {
                TextInserter.textOf(current.node)
            } catch (e: RuntimeException) {
                ""
            }
            val edited = CorrectionAnalysis.extract(text, current.before, current.after)
            if (edited != null && CorrectionAnalysis.overlap(current.inserted, edited) >= MIN_OVERLAP) {
                current.latest = edited
            } else if (current.latest != null) {
                // The box has moved on (sent, cleared or rewritten): learn from the last good look.
                finish()
                return
            }
            if (SystemClock.uptimeMillis() - current.startedAt >= WATCH_MS) {
                finish()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    /** Start watching the box from a dictation that was just typed. */
    fun watch(result: TextInserter.Result, appPackage: String?) {
        finish()
        if (!prefs.learnFromCorrections) return
        val node = result.node ?: return
        if (result.inserted.isBlank()) return
        watch = Watch(node, appPackage, result.inserted, result.before, result.after, SystemClock.uptimeMillis())
        handler.postDelayed(poll, FIRST_POLL_MS)
    }

    /** Stop watching and learn from what was seen so far. */
    fun finish() {
        handler.removeCallbacks(poll)
        val current = watch ?: return
        watch = null
        val edited = current.latest ?: return
        val findings = CorrectionAnalysis.analyse(current.inserted, edited, atStartOfBox = current.before.isBlank())
        current.appPackage?.let { prefs.recordHabits(it, findings.keptFinalStop, findings.loweredStart) }
        val added = prefs.addLearned(findings.words)
        if (added.isNotEmpty()) onLearned(added)
    }

    private companion object {
        const val FIRST_POLL_MS = 700L
        const val POLL_MS = 1_500L
        const val WATCH_MS = 60_000L
        /** Below this, you've replaced the text rather than corrected it. */
        const val MIN_OVERLAP = 0.5
    }
}
