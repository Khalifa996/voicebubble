package io.github.khalifa996.voicebubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Shows the mic bubble just above the keyboard whenever a keyboard is open, runs the
 * dictation when it's tapped, and types the result into the field you were in.
 *
 * Uses an accessibility service because that's the only way an app can know when another
 * app's keyboard is on screen and type into its text box. It listens to window and focus
 * changes only, and never reads the screen's text.
 */
class BubbleService : AccessibilityService(), DictationSession.Listener, CloudDictation.Listener {

    private lateinit var prefs: Prefs
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false
    private var connected = false

    private var state = BubbleView.Mode.IDLE
    private var session: DictationSession? = null
    private var meter: MicLevelMeter? = null
    private var cloud: CloudDictation? = null
    private var engineNote = ""
    private var contextBefore = ""
    private lateinit var learner: CorrectionLearner
    private var target: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null

    private val imeBounds = Rect()
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragX: Int? = null
    private var dragY: Int? = null

    private val refreshRunnable = Runnable { refresh() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        learner = CorrectionLearner(prefs) { words ->
            toast(getString(R.string.toast_learned, words.joinToString(", ")))
        }
        windowManager = getSystemService(WindowManager::class.java)
        layoutParams = createLayoutParams()
        bubble = BubbleView(this).apply {
            onTap = { onBubbleTapped() }
            onCancel = { cancelDictation() }
            onDragStart = {
                dragStartX = prefs.offsetX
                dragStartY = prefs.offsetY
            }
            onDragMove = { dx, dy ->
                // Gravity is END, so x counts from the right edge: dragging right shrinks it.
                dragX = dragStartX - dx.toInt()
                dragY = dragStartY + dy.toInt()
                refresh()
            }
            onDragEnd = {
                dragX?.let { prefs.offsetX = it }
                dragY?.let { prefs.offsetY = it }
                dragX = null
                dragY = null
            }
        }
        connected = true
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (connected) scheduleRefresh()
    }

    // We give no spoken feedback, so there's nothing to interrupt. Deliberately does not
    // cancel a dictation in progress.
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        if (::learner.isInitialized) learner.finish()
        cloud?.cancel()
        cloud = null
        stopMeter()
        session?.cancel()
        session = null
        handler.removeCallbacksAndMessages(null)
        hide()
        bubble = null
        super.onDestroy()
    }

    // ---- Showing, hiding and placing the bubble ----

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_DELAY_MS)
    }

    private fun refresh() {
        if (!connected) return
        val view = bubble ?: return
        val busy = state != BubbleView.Mode.IDLE
        val ime = findKeyboardWindow()
        if (ime == null) {
            // Keep the bubble while listening or tidying up, even if the keyboard closes.
            if (!busy) hide()
            return
        }
        if (!busy && isPasswordFieldFocused()) {
            hide()
            return
        }
        ime.getBoundsInScreen(imeBounds)
        val screen = screenBounds()
        val margin = dp(6)
        val size = view.windowHeight
        val baseX = (screen.right - imeBounds.right) + margin
        val baseY = imeBounds.top - size - margin
        val x = (baseX + (dragX ?: prefs.offsetX)).coerceIn(0, maxOf(0, screen.width() - size))
        val y = (baseY + (dragY ?: prefs.offsetY)).coerceIn(0, maxOf(0, screen.height() - size))
        show(x, y)
    }

    /** The whole display the bubble lives on (cover or inner screen on a Fold). */
    private fun screenBounds(): Rect = try {
        windowManager.maximumWindowMetrics.bounds
    } catch (e: RuntimeException) {
        val metrics = resources.displayMetrics
        Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun findKeyboardWindow(): AccessibilityWindowInfo? = try {
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (e: RuntimeException) {
        null
    }

    private fun isPasswordFieldFocused(): Boolean = try {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isPassword == true
    } catch (e: RuntimeException) {
        false
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Never take focus, so the keyboard and the app's text box stay active.
        gravity = Gravity.TOP or Gravity.END
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "VoiceBubble"
    }

    private fun show(x: Int, y: Int) {
        val view = bubble ?: return
        val params = layoutParams ?: return
        if (attached && params.x == x && params.y == y) return
        params.x = x
        params.y = y
        try {
            if (attached) {
                windowManager.updateViewLayout(view, params)
            } else {
                windowManager.addView(view, params)
                attached = true
            }
        } catch (e: RuntimeException) {
            attached = view.isAttachedToWindow
        }
    }

    private fun hide() {
        val view = bubble ?: return
        if (!attached) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            // Already removed.
        }
        attached = false
    }

    // ---- Dictation ----

    private fun onBubbleTapped() {
        when (state) {
            BubbleView.Mode.IDLE -> startDictation()
            BubbleView.Mode.LISTENING -> {
                val recording = cloud
                if (recording != null) {
                    recording.stop()
                    setState(BubbleView.Mode.PROCESSING)
                } else {
                    session?.stop()
                }
            }
            BubbleView.Mode.PROCESSING -> Unit
        }
    }

    /** The ✕ button: stop listening and throw away what was said. Nothing is typed. */
    private fun cancelDictation() {
        if (state != BubbleView.Mode.LISTENING) return
        stopMeter()
        cloud?.cancel()
        cloud = null
        session?.cancel()
        session = null
        target = null
        setState(BubbleView.Mode.IDLE)
    }

    /** Start measuring mic volume for the sound bars. Must run before the recogniser starts. */
    private fun startMeter() {
        stopMeter()
        if (!prefs.liveBars) return
        val newMeter = MicLevelMeter { level -> bubble?.setMicLevel(level) }
        if (newMeter.start()) meter = newMeter
    }

    private fun stopMeter() {
        meter?.stop()
        meter = null
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_need_mic))
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val focused = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: RuntimeException) {
            null
        }
        target = focused
        targetPackage = focused?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        // Learn from the last dictation before this one changes the box.
        learner.finish()
        contextBefore = if (prefs.useContext) TextInserter.textBeforeCursor(this, CONTEXT_CHARS) else ""
        setState(BubbleView.Mode.LISTENING)

        // Best: record the mic ourselves and let Whisper transcribe it (needs internet).
        if (prefs.cloudSpeech && prefs.isCleanupConfigured && isOnline()) {
            val recording = CloudDictation(prefs, this)
            if (recording.start()) {
                cloud = recording
                engineNote = getString(R.string.engine_cloud)
                return
            }
        }
        startOnDevice()
    }

    /** Fallback: the phone's own speech recogniser (works offline). */
    private fun startOnDevice() {
        engineNote = getString(R.string.engine_device)
        // Meter first: Android hands the mic to the newest recording, so the recogniser
        // (started second) always gets it and dictation can't be affected by the bars.
        startMeter()
        val newSession = DictationSession(this, prefs, this)
        session = newSession
        newSession.start()
    }

    private fun isOnline(): Boolean = try {
        getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    } catch (e: RuntimeException) {
        true
    }

    // ---- Cloud recording callbacks ----

    override fun onMicLevel(level: Float) {
        bubble?.setMicLevel(level)
    }

    override fun onCloudAutoStopped() {
        if (cloud != null && state == BubbleView.Mode.LISTENING) setState(BubbleView.Mode.PROCESSING)
    }

    override fun onCloudFinished(text: String, lostParts: Int) {
        cloud = null
        if (lostParts > 0) toast(getString(R.string.toast_parts_lost))
        handleTranscript(text)
    }

    override fun onCloudFailed(message: String, silenced: Boolean) {
        cloud = null
        if (silenced) {
            // This phone won't let the bubble record by itself. Switch to the phone's
            // recogniser straight away, and stay on it.
            prefs.cloudSpeech = false
            toast(getString(R.string.toast_cloud_muted))
            if (state == BubbleView.Mode.LISTENING) startOnDevice() else setState(BubbleView.Mode.IDLE)
            return
        }
        setState(BubbleView.Mode.IDLE)
        toast(getString(R.string.toast_cloud_failed, message))
    }

    // ---- On-device recogniser callbacks ----

    override fun onPartial(text: String) {
        // The bubble shows sound bars rather than the words; words arriving mean you're talking.
        bubble?.onSpeech()
    }

    override fun onLevel(rmsDb: Float) {
        bubble?.setLevel(rmsDb)
    }

    override fun onFinished(text: String) {
        session = null
        stopMeter()
        handleTranscript(text)
    }

    /** Tidy up the transcript (if switched on) and type it. Shared by both engines. */
    private fun handleTranscript(text: String) {
        val raw = text.trim()
        if (raw.isEmpty()) {
            setState(BubbleView.Mode.IDLE)
            toast(getString(R.string.toast_nothing_heard))
            return
        }
        prefs.lastHeard = raw
        if (!prefs.aiCleanup || !prefs.isCleanupConfigured) {
            deliver(raw, getString(R.string.note_not_tidied))
            setState(BubbleView.Mode.IDLE)
            return
        }
        setState(BubbleView.Mode.PROCESSING)
        CleanupClient.clean(prefs, raw, targetPackage, contextBefore) { result ->
            if (!connected) return@clean
            val note = if (result.cleaned) {
                getString(R.string.note_tidied)
            } else {
                getString(R.string.note_skipped, result.error ?: "unknown")
            }
            deliver(result.text, note)
            if (!result.cleaned && result.error != null) {
                toast(getString(R.string.toast_raw_fallback, result.error))
            }
            setState(BubbleView.Mode.IDLE)
        }
    }

    override fun onFailed(message: String, micProblem: Boolean) {
        session = null
        val meterWasOn = meter != null
        stopMeter()
        setState(BubbleView.Mode.IDLE)
        if (micProblem && meterWasOn) {
            // The sound bars may have got in the recogniser's way on this phone. Turn them
            // off so dictation keeps working; they can be switched back on in the app.
            prefs.liveBars = false
            toast(getString(R.string.toast_live_bars_off))
        } else {
            toast(message)
        }
    }

    /** Types [text] into the box and records what happened for the "Last dictation" panel. */
    private fun deliver(text: String, note: String) {
        if (text.isBlank()) {
            prefs.lastTyped = ""
            prefs.lastHow = note
            return
        }
        val result = try {
            TextInserter.insert(this, target, text)
        } catch (e: RuntimeException) {
            TextInserter.Result(TextInserter.Outcome.COPIED)
        }
        val outcome = result.outcome
        if (outcome != TextInserter.Outcome.COPIED) learner.watch(result, targetPackage)
        if (outcome == TextInserter.Outcome.COPIED) toast(getString(R.string.toast_copied))
        val how = when (outcome) {
            TextInserter.Outcome.TYPED -> getString(R.string.how_typed)
            TextInserter.Outcome.PASTED -> getString(R.string.how_pasted)
            TextInserter.Outcome.COPIED -> getString(R.string.how_copied)
        }
        prefs.lastTyped = text
        prefs.lastHow = "$how · $note · $engineNote"
        target = null
    }

    private fun setState(newState: BubbleView.Mode) {
        state = newState
        bubble?.setMode(newState)
        scheduleRefresh()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REFRESH_DELAY_MS = 60L
        const val CONTEXT_CHARS = 400
    }
}
