package io.github.khalifa996.voicebubble

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One tap-to-start, tap-to-stop dictation that records the microphone itself and has the
 * Worker turn it into text with Whisper. Nothing is cut off at pauses: everything between
 * the two taps is recorded.
 *
 * Built to be quick when you tap stop:
 * - A connection to the Worker is opened while you start talking, and reused.
 * - At each short pause the audio so far is sent off and transcribed in the background,
 *   so when you stop only the last few seconds are left.
 * - That last piece goes in one request that also does Claude's tidy-up on the Worker,
 *   so there's a single round trip after you stop.
 * - Better still, it doesn't wait for you to tap stop. Whenever you pause, it works out the
 *   finished text as if you'd stopped there ("speculation"). If you then tap stop without
 *   saying anything more, that answer is used straight away. If you carry on, it's thrown
 *   away. Same audio, same models, so accuracy is unchanged; it just starts sooner.
 *
 * The same recording drives the sound bars, so they follow your real volume. Audio is held
 * in memory only, sent to your own Worker, and never saved. Listener calls arrive on the
 * main thread.
 */
class CloudDictation(
    prefs: Prefs,
    private val options: Options,
    private val listener: Listener,
) {

    /** What to do with the text once it's transcribed. */
    class Options(
        val clean: Boolean,
        val quick: Boolean,
        val app: String?,
        val context: String,
        val habits: List<String>,
    )

    class Result(
        /** What Whisper heard. */
        val heard: String,
        /** What to type: tidied up, or the same as [heard]. */
        val text: String,
        val cleaned: Boolean,
        /** Claude was skipped because the text was already clean. */
        val skipped: Boolean,
        /** Why the tidy-up or the last piece failed, if it did. */
        val error: String?,
        val whisperMs: Long,
        val claudeMs: Long,
        /** From tapping stop to having the text. */
        val waitMs: Long,
        /** Pieces that couldn't be sent (connection trouble). */
        val lostParts: Int,
    )

    interface Listener {
        /** Mic loudness, 0 (silence) to 1 (loud), about 50 times a second. */
        fun onMicLevel(level: Float)
        /** Recording ended by itself (long silence or time limit); text is on its way. */
        fun onCloudAutoStopped()
        fun onCloudFinished(result: Result)
        /** Nothing usable. [silenced]: Android wouldn't let this app hear the mic at all. */
        fun onCloudFailed(message: String, silenced: Boolean)
    }

    private val url = prefs.workerUrl
    private val token = prefs.appToken
    private val language = prefs.language
    private val vocabulary = prefs.vocabularyList

    private val main = Handler(Looper.getMainLooper())
    private val uploader = Executors.newSingleThreadExecutor()
    private val speculator = Executors.newSingleThreadExecutor()
    private val texts = Collections.synchronizedList(mutableListOf<String>())
    private val lostParts = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var stoppedAt = 0L
    @Volatile private var lastError = "no connection"

    /** How many 20 ms frames of speech so far: a speculation is still valid if this hasn't moved. */
    @Volatile private var speechFrames = 0

    /** Speech count at the moment you tapped stop (ignoring the tap), once known. */
    @Volatile private var finalKey = -1

    /**
     * A guess at the finished text, made at a pause. [previous] becomes the transcript of
     * every piece sent before the pause, and no later ones, so nothing can be doubled.
     */
    private class Speculation(val key: Int, val tail: ByteArray, val previous: Future<String>) {
        val result = CompletableFuture<Pair<JSONObject, String>?>()
        val started = AtomicBoolean(false)
    }
    private val specLock = Any()
    private var specLatest: Speculation? = null
    private var specPending: Speculation? = null
    private var specBusy = false

    /** Returns false if the microphone couldn't be opened. */
    @SuppressLint("MissingPermission") // Checked by the caller before dictation starts.
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL)
                        .setEncoding(ENCODING)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2)) // 1 s of headroom
                .build()
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }
        running = true
        // Open the connection now, while you're talking, so it's ready when it's needed.
        uploader.execute { warmUp() }
        Thread({ recordLoop(record) }, "VoiceBubble-recorder").start()
        return true
    }

    /** Stop recording and deliver the text. */
    fun stop() {
        if (stoppedAt == 0L) stoppedAt = SystemClock.elapsedRealtime()
        running = false
    }

    /** Throw everything away without calling the listener. */
    fun cancel() {
        cancelled = true
        running = false
        uploader.shutdownNow()
        speculator.shutdownNow()
    }

    private fun recordLoop(record: AudioRecord) {
        val frame = ByteArray(FRAME_BYTES)
        var segment = ByteArrayOutputStream(SAMPLE_RATE * 2 * 10)
        var segmentFrames = 0
        var segmentHasSpeech = false
        var quietRun = 0
        var framesSinceSpeech = 0
        var totalFrames = 0
        var heardAnything = false
        var silenced = false
        var autoStopped = false
        // Speech count per recent frame, to ignore the last moment (the tap itself can be loud).
        val recentSpeech = IntArray(RECENT_FRAMES)

        try {
            while (running) {
                val count = record.read(frame, 0, frame.size)
                if (count < 0) break
                if (count < 2) continue

                var sum = 0.0
                var i = 0
                while (i + 1 < count) {
                    val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toDouble()
                    sum += sample * sample
                    i += 2
                }
                if (sum > 0.0) heardAnything = true
                val db = if (sum > 0.0) 20.0 * log10(sqrt(sum / (count / 2)) / 32768.0) else -120.0
                val isSpeech = db > SPEECH_DB

                segment.write(frame, 0, count)
                segmentFrames++
                totalFrames++
                if (isSpeech) {
                    segmentHasSpeech = true
                    quietRun = 0
                    framesSinceSpeech = 0
                    speechFrames++
                } else {
                    quietRun++
                    framesSinceSpeech++
                }
                recentSpeech[totalFrames % RECENT_FRAMES] = speechFrames

                // A pause: get the finished text ready in case this is the end.
                if (quietRun == SPEC_PAUSE_FRAMES && speechFrames > 0) {
                    speculate(speechFrames, if (segmentHasSpeech) segment.toByteArray() else ByteArray(0))
                }

                val level = ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0).toFloat()
                main.post { if (running) listener.onMicLevel(level) }

                // Exact digital silence from the start means Android has muted this recording.
                if (!heardAnything && totalFrames >= SILENCED_AFTER_FRAMES) {
                    silenced = true
                    break
                }

                // Send a piece off at a short pause, or at the size limit if you never pause.
                val atPause = segmentFrames >= MIN_SEGMENT_FRAMES && quietRun >= PAUSE_FRAMES
                if (atPause || segmentFrames >= MAX_SEGMENT_FRAMES) {
                    if (segmentHasSpeech) submit(segment.toByteArray())
                    segment = ByteArrayOutputStream(SAMPLE_RATE * 2 * 10)
                    segmentFrames = 0
                    segmentHasSpeech = false
                }

                // Walked away, or forgot the mic was on.
                if (framesSinceSpeech >= AUTO_STOP_SILENCE_FRAMES || totalFrames >= MAX_TOTAL_FRAMES) {
                    autoStopped = true
                    break
                }
            }
        } catch (e: Exception) {
            lastError = "microphone problem"
        } finally {
            running = false
            if (stoppedAt == 0L) stoppedAt = SystemClock.elapsedRealtime()
            try {
                record.stop()
            } catch (e: Exception) {
                // Already stopped.
            }
            record.release()
        }

        if (cancelled) return
        if (silenced) {
            uploader.shutdownNow()
            speculator.shutdownNow()
            main.post { if (!cancelled) listener.onCloudFailed("Android muted the microphone", silenced = true) }
            return
        }
        if (autoStopped) main.post { if (!cancelled) listener.onCloudAutoStopped() }

        // Keep the same 0.3 s of quiet at the end that a speculation would have had.
        var lastPiece = if (segmentHasSpeech) segment.toByteArray() else ByteArray(0)
        val extraQuiet = (quietRun - SPEC_PAUSE_FRAMES) * FRAME_BYTES
        if (extraQuiet > 0 && lastPiece.isNotEmpty()) {
            lastPiece = lastPiece.copyOf(maxOf(0, lastPiece.size - extraQuiet))
        }
        // Speech up to just before you tapped stop, so the tap's own noise doesn't count.
        val key = if (totalFrames > TAP_FRAMES) {
            recentSpeech[(totalFrames - 1 - TAP_FRAMES) % RECENT_FRAMES]
        } else {
            speechFrames
        }
        finalKey = key
        try {
            uploader.execute { finish(lastPiece, key) }
        } catch (e: Exception) {
            // Cancelled while finishing.
        }
    }

    // ---- Speculation ----

    /** Start working out the finished text as if you'd stopped now. Only the newest guess matters. */
    private fun speculate(key: Int, tail: ByteArray) {
        if (cancelled) return
        // Gives the text so far once every piece sent before this pause has been transcribed.
        val previous = try {
            uploader.submit(java.util.concurrent.Callable { textSoFar() })
        } catch (e: Exception) {
            return
        }
        val spec = Speculation(key, tail, previous)
        synchronized(specLock) {
            specLatest = spec
            specPending?.result?.complete(null) // Superseded before it started.
            specPending = spec
            if (!specBusy) {
                specBusy = true
                try {
                    speculator.execute { runSpeculations() }
                } catch (e: Exception) {
                    specBusy = false
                }
            }
        }
    }

    private fun runSpeculations() {
        while (true) {
            val spec = synchronized(specLock) {
                val next = specPending
                specPending = null
                if (next == null) specBusy = false
                next
            } ?: return
            if (!spec.started.compareAndSet(false, true)) continue
            val outcome = try {
                val previous = spec.previous.get()
                // You've said more since this pause: a newer guess will come at the next one.
                val spokenSince = (if (finalKey >= 0) finalKey else speechFrames) != spec.key
                if (cancelled || spokenSince) {
                    null
                } else {
                    // Only a complete success counts. If Whisper or Claude stumbled, the normal
                    // path runs instead (with retries) rather than using a half-finished guess.
                    dictateRequest(previous, spec.tail)
                        ?.takeIf { it.first == 200 }
                        ?.let { JSONObject(it.second) }
                        ?.takeIf { it.optString("error", "").isEmpty() }
                        ?.let { it to previous }
                }
            } catch (e: Exception) {
                null
            }
            spec.result.complete(outcome)
        }
    }

    private fun submit(pcm: ByteArray) {
        try {
            uploader.execute { upload(pcm) }
        } catch (e: Exception) {
            // Cancelled.
        }
    }

    // ---- Network (all on the upload thread, one request at a time, so text stays in order) ----

    private fun warmUp() {
        try {
            val conn = URL("$url/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            // Reading the reply to the end lets the connection go back to the pool for reuse.
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            // Only a head start; the real request will connect if this didn't.
        }
    }

    /** A piece sent while you're still talking. */
    private fun upload(pcm: ByteArray) {
        val wav = wavHeader(pcm.size) + pcm
        val headers = mutableMapOf(
            "Content-Type" to "audio/wav",
            "X-Language" to language,
        )
        if (vocabulary.isNotEmpty()) headers["X-Vocabulary"] = encode(vocabulary.joinToString("\n"))
        val previous = textSoFar().takeLast(PROMPT_TAIL_CHARS)
        if (previous.isNotEmpty()) headers["X-Previous"] = encode(previous)

        for (attempt in 1..ATTEMPTS) {
            if (cancelled) return
            try {
                val (code, body) = post("$url/transcribe", wav, headers)
                if (code == 200) {
                    texts.add(JSONObject(body).optString("text", "").trim())
                    return
                }
                lastError = CleanupClient.describe(code, body)
                // Our own mistake (bad token, wrong URL): trying again won't help.
                if (code in 400..499) break
            } catch (e: IOException) {
                lastError = "no connection"
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
            if (!pause(attempt)) return
        }
        lostParts.incrementAndGet()
    }

    /** The finished text: from the speculation if you didn't say anything more, otherwise worked out now. */
    private fun finish(lastPiece: ByteArray, key: Int) {
        val spec = synchronized(specLock) {
            specLatest?.takeIf { it.key == key }?.also { candidate ->
                // Not started yet: quicker to just do it here.
                if (candidate.started.compareAndSet(false, true)) {
                    if (specPending === candidate) specPending = null
                    candidate.result.complete(null)
                }
            }
        }
        val guessed = try {
            spec?.result?.get(SPEC_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }
        if (guessed != null && !cancelled) {
            deliver(parse(guessed.first, guessed.second, lostParts.get()))
            return
        }
        finishNow(lastPiece)
    }

    private fun finishNow(lastPiece: ByteArray) {
        val previous = textSoFar()
        var result: Result? = null
        for (attempt in 1..ATTEMPTS) {
            if (cancelled) return
            try {
                val (code, response) = dictateRequest(previous, lastPiece) ?: continue
                if (code == 200) {
                    val json = JSONObject(response)
                    val failedLastPiece = json.optString("error", "") == "transcription_failed"
                    result = parse(json, previous, lostParts.get() + if (failedLastPiece) 1 else 0)
                    break
                }
                lastError = CleanupClient.describe(code, response)
                if (code in 400..499) break
            } catch (e: IOException) {
                lastError = "no connection"
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
            if (!pause(attempt)) return
        }

        // Couldn't reach the Worker at the end: hand over whatever was transcribed earlier.
        deliver(
            result ?: Result(
                heard = previous,
                text = previous,
                cleaned = false,
                skipped = false,
                error = lastError,
                whisperMs = 0L,
                claudeMs = 0L,
                waitMs = SystemClock.elapsedRealtime() - stoppedAt,
                lostParts = lostParts.get() + if (lastPiece.isNotEmpty()) 1 else 0,
            ),
        )
    }

    private fun deliver(final: Result) {
        main.post {
            if (cancelled) return@post
            if (final.heard.isEmpty() && final.lostParts > 0) {
                listener.onCloudFailed(final.error ?: lastError, silenced = false)
            } else {
                listener.onCloudFinished(final)
            }
        }
        uploader.shutdown()
        speculator.shutdownNow()
    }

    private fun parse(json: JSONObject, previous: String, lost: Int): Result {
        val ms = json.optJSONObject("ms")
        val error = json.optString("error", "").ifEmpty { null }
        return Result(
            heard = json.optString("heard", previous).trim(),
            text = json.optString("text", previous).trim(),
            cleaned = json.optBoolean("cleaned", false),
            skipped = json.optBoolean("skipped", false),
            error = error?.replace('_', ' '),
            whisperMs = ms?.optLong("whisper", 0L) ?: 0L,
            claudeMs = ms?.optLong("claude", 0L) ?: 0L,
            waitMs = SystemClock.elapsedRealtime() - stoppedAt,
            lostParts = lost,
        )
    }

    /** POST /dictate: the text so far, plus [pcm] (may be empty), plus what to do with it. */
    private fun dictateRequest(previous: String, pcm: ByteArray): Pair<Int, String>? {
        if (cancelled) return null
        val opts = JSONObject()
            .put("language", language)
            .put("vocabulary", JSONArray(vocabulary))
            .put("previous", previous)
            .put("clean", options.clean)
            .put("quick", options.quick)
            .put("app", options.app.orEmpty())
            .put("context", options.context)
            .put("habits", JSONArray(options.habits))
        val optsBytes = opts.toString().toByteArray(Charsets.UTF_8)
        val wav = if (pcm.isEmpty()) ByteArray(0) else wavHeader(pcm.size) + pcm
        val body = ByteArray(4 + optsBytes.size + wav.size)
        for (i in 0..3) body[i] = (optsBytes.size shr (8 * i)).toByte()
        System.arraycopy(optsBytes, 0, body, 4, optsBytes.size)
        System.arraycopy(wav, 0, body, 4 + optsBytes.size, wav.size)
        return post("$url/dictate", body, mapOf("Content-Type" to "application/octet-stream"))
    }

    private fun textSoFar(): String = texts.toList().filter { it.isNotBlank() }.joinToString(" ")

    /** Wait a little before trying again. False if cancelled meanwhile. */
    private fun pause(attempt: Int): Boolean = try {
        Thread.sleep(RETRY_DELAY_MS * attempt)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun post(target: String, body: ByteArray, headers: Map<String, String>): Pair<Int, String> {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        // Read to the end and close, but don't disconnect: that keeps the connection for the next request.
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        return code to text
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** A standard 44-byte header for 16 kHz, 16-bit, mono audio. */
    private fun wavHeader(dataSize: Int): ByteArray {
        val header = ByteArray(44)
        fun text(offset: Int, value: String) = value.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun int32(offset: Int, value: Int) {
            for (i in 0..3) header[offset + i] = (value shr (8 * i)).toByte()
        }
        fun int16(offset: Int, value: Int) {
            for (i in 0..1) header[offset + i] = (value shr (8 * i)).toByte()
        }
        text(0, "RIFF")
        int32(4, 36 + dataSize)
        text(8, "WAVE")
        text(12, "fmt ")
        int32(16, 16)
        int16(20, 1) // PCM
        int16(22, 1) // mono
        int32(24, SAMPLE_RATE)
        int32(28, SAMPLE_RATE * 2)
        int16(32, 2)
        int16(34, 16)
        text(36, "data")
        int32(40, dataSize)
        return header
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 // 20 ms
        const val FRAMES_PER_SECOND = 50

        // Volume, in dB below full scale: the range shown on the bars, and what counts as speech.
        const val QUIET_DB = -55.0
        const val LOUD_DB = -15.0
        const val SPEECH_DB = -45.0

        // Pieces: sent at the first half-second pause after 4 s, or at 25 s regardless.
        const val MIN_SEGMENT_FRAMES = 4 * FRAMES_PER_SECOND
        const val MAX_SEGMENT_FRAMES = 25 * FRAMES_PER_SECOND
        const val PAUSE_FRAMES = 25
        const val AUTO_STOP_SILENCE_FRAMES = 30 * FRAMES_PER_SECOND
        const val MAX_TOTAL_FRAMES = 5 * 60 * FRAMES_PER_SECOND
        const val SILENCED_AFTER_FRAMES = 75 // 1.5 s

        // Speculation: guess the finished text after 0.3 s of quiet; ignore the last 0.2 s
        // before the tap when checking you didn't say more; wait up to 12 s for a guess in flight.
        const val SPEC_PAUSE_FRAMES = 15
        const val TAP_FRAMES = 10
        const val RECENT_FRAMES = 64
        const val SPEC_WAIT_MS = 12_000L

        const val PROMPT_TAIL_CHARS = 200
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}
