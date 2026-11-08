package io.github.khalifa996.voicebubble

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One tap-to-start, tap-to-stop dictation.
 *
 * Uses the phone's own speech recogniser, preferring the on-device engine (fast, private,
 * streams words as you speak). Android recognisers normally stop at the first pause, so this
 * keeps listening across pauses until the user taps stop: on Android 13+ it asks for a
 * "segmented session" (one long session), and on recognisers that ignore that it simply
 * starts listening again after each phrase. All callbacks run on the main thread.
 */
class DictationSession(
    private val context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {

    interface Listener {
        /** Everything heard so far, including words not yet final. */
        fun onPartial(text: String)
        fun onLevel(rmsDb: Float)
        /** The full transcript. Called once, unless [onFailed] is called instead. */
        fun onFinished(text: String)
        fun onFailed(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var segmented = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    private var triedOnlineFallback = false

    private val segments = mutableListOf<String>()
    private var partial = ""

    private var stopRequested = false
    private var finished = false
    private var startedAt = 0L
    private var listenStartedAt = 0L
    private var lastSpeechAt = 0L
    private var quickFailures = 0
    private var busyRetries = 0

    fun start() {
        startedAt = now()
        lastSpeechAt = startedAt
        if (!createRecognizer(prefs.preferOnDevice)) {
            fail("No speech recogniser found. Install or update \"Speech Services by Google\" from the Play Store.")
            return
        }
        listen()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Stop listening and deliver what was said. */
    fun stop() {
        if (finished || stopRequested) return
        stopRequested = true
        recognizer?.stopListening()
        // Some recognisers never send final results after stop; don't wait forever.
        handler.postDelayed(stopTimeout, STOP_GRACE_MS)
    }

    /** Throw everything away without calling the listener. */
    fun cancel() {
        if (finished) return
        finished = true
        release()
    }

    private fun createRecognizer(preferOnDevice: Boolean): Boolean {
        releaseRecognizer()
        val useOnDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!useOnDevice && !SpeechRecognizer.isRecognitionAvailable(context)) return false
        val created = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        created.setRecognitionListener(Callbacks(created))
        recognizer = created
        onDevice = useOnDevice
        return true
    }

    private fun listen() {
        if (finished) return
        partial = ""
        listenStartedAt = now()
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: RuntimeException) {
            finishOrFail("Couldn't start listening: ${e.message}")
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ extras. Recognisers that don't know them just ignore them.
            putExtra(EXTRA_ENABLE_FORMATTING, FORMATTING_OPTIMIZE_QUALITY)
            putExtra(EXTRA_MASK_OFFENSIVE_WORDS, false)
            val words = prefs.vocabularyList
            if (words.isNotEmpty()) putStringArrayListExtra(EXTRA_BIASING_STRINGS, ArrayList(words))
            if (segmented) {
                putExtra(EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MAX_SESSION_MS)
            }
        }
    }

    private inner class Callbacks(private val owner: SpeechRecognizer) : RecognitionListener {

        /** Ignore late callbacks from a recogniser we've already replaced or released. */
        private fun live() = !finished && owner === recognizer

        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {
            if (live()) lastSpeechAt = now()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (live()) listener.onLevel(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            val text = bestOf(partialResults)
            if (text.isNotBlank()) {
                partial = text
                lastSpeechAt = now()
                listener.onPartial(currentText())
            }
        }

        override fun onResults(results: Bundle?) {
            if (!live()) return
            quickFailures = 0
            commit(bestOf(results))
            if (stopRequested) finish() else handler.post { listen() }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!live()) return
            quickFailures = 0
            commit(bestOf(segmentResults))
            listener.onPartial(currentText())
        }

        override fun onEndOfSegmentedSession() {
            if (!live()) return
            if (stopRequested) finish() else handler.post { listen() }
        }

        override fun onError(error: Int) {
            if (live()) handleError(error)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleError(error: Int) {
        val quick = now() - listenStartedAt < QUICK_FAIL_MS
        when (error) {
            // Silence, nothing recognised, or the recogniser tripping over a restart:
            // keep what we have and carry on listening until the user taps stop.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                commit(partial)
                if (stopRequested) {
                    finish()
                    return
                }
                if (quick) {
                    // Instant failure: drop the optional long-session mode in case that's the cause.
                    segmented = false
                    if (++quickFailures >= MAX_QUICK_FAILURES) {
                        finishOrFail("The speech service keeps stopping. Try again in a moment.")
                        return
                    }
                }
                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) createRecognizer(onDevice)
                handler.post { listen() }
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                finishOrFail("Microphone access is off. Open VoiceBubble and allow it.")

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                // The on-device model for this language isn't installed: use the online engine.
                if (onDevice && !triedOnlineFallback) {
                    triedOnlineFallback = true
                    if (createRecognizer(preferOnDevice = false)) {
                        handler.post { listen() }
                        return
                    }
                }
                finishOrFail("${prefs.language} isn't available for speech recognition. Check the language in VoiceBubble.")
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                if (!stopRequested && busyRetries++ < 2) {
                    createRecognizer(onDevice)
                    handler.postDelayed({ listen() }, 250)
                } else {
                    finishOrFail("The speech service is busy. Try again.")
                }
            }

            else -> {
                if (stopRequested || hasText()) {
                    finish()
                    return
                }
                if (onDevice && !triedOnlineFallback) {
                    triedOnlineFallback = true
                    if (createRecognizer(preferOnDevice = false)) {
                        handler.post { listen() }
                        return
                    }
                }
                finishOrFail(describe(error))
            }
        }
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "No connection for speech recognition. Download the offline speech model in VoiceBubble."
        SpeechRecognizer.ERROR_AUDIO -> "Couldn't use the microphone. Is another app recording?"
        SpeechRecognizer.ERROR_SERVER -> "The speech service had a problem. Try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests to the speech service. Wait a moment."
        else -> "Speech recognition stopped (code $error)."
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (finished) return
            val t = now()
            // Auto-stop if the user walked away or forgot the mic was on.
            if (!stopRequested && (t - lastSpeechAt > SILENCE_AUTO_STOP_MS || t - startedAt > MAX_SESSION_MS)) {
                stop()
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val stopTimeout = Runnable { finish() }

    private fun commit(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) segments.add(trimmed)
        partial = ""
    }

    private fun currentText(): String =
        (segments + partial).filter { it.isNotBlank() }.joinToString(" ")

    private fun hasText(): Boolean = segments.isNotEmpty() || partial.isNotBlank()

    private fun finishOrFail(message: String) {
        if (hasText()) finish() else fail(message)
    }

    private fun finish() {
        if (finished) return
        commit(partial)
        val text = segments.joinToString(" ").trim()
        finished = true
        release()
        listener.onFinished(text)
    }

    private fun fail(message: String) {
        if (finished) return
        finished = true
        release()
        listener.onFailed(message)
    }

    private fun release() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(stopTimeout)
        releaseRecognizer()
    }

    private fun releaseRecognizer() {
        val old = recognizer ?: return
        recognizer = null
        // Destroy outside the current callback; its own callbacks are ignored via live().
        handler.post {
            try {
                old.cancel()
                old.destroy()
            } catch (e: RuntimeException) {
                // Already gone.
            }
        }
    }

    private fun bestOf(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        // Android 13+ RecognizerIntent keys, written out so the build doesn't depend on
        // exact constant names. Unknown keys are ignored by the recogniser.
        const val EXTRA_SEGMENTED_SESSION = "android.speech.extra.SEGMENTED_SESSION"
        const val EXTRA_ENABLE_FORMATTING = "android.speech.extra.ENABLE_FORMATTING"
        const val FORMATTING_OPTIMIZE_QUALITY = "quality"
        const val EXTRA_MASK_OFFENSIVE_WORDS = "android.speech.extra.MASK_OFFENSIVE_WORDS"
        const val EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS"

        const val MAX_SESSION_MS = 5 * 60 * 1000L
        const val SILENCE_AUTO_STOP_MS = 30_000L
        const val STOP_GRACE_MS = 2_500L
        const val WATCHDOG_MS = 1_000L
        const val QUICK_FAIL_MS = 700L
        const val MAX_QUICK_FAILURES = 4
    }
}
