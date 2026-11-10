package io.github.khalifa996.voicebubble

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One tap-to-start, tap-to-stop dictation that records the microphone itself and has the
 * Worker turn it into text with Whisper. Nothing is cut off at pauses: everything between
 * the two taps is recorded.
 *
 * While you talk, the recording is sent off in pieces (cut at a pause, every 15 to 28
 * seconds), so by the time you tap stop only the last piece is left to do. The same
 * recording drives the sound bars, so they follow your real volume.
 *
 * Audio is held in memory only, sent to your own Worker, and never saved.
 * All listener calls arrive on the main thread.
 */
class CloudDictation(prefs: Prefs, private val listener: Listener) {

    interface Listener {
        /** Mic loudness, 0 (silence) to 1 (loud), about 50 times a second. */
        fun onMicLevel(level: Float)
        /** Recording ended by itself (long silence or time limit); text is on its way. */
        fun onCloudAutoStopped()
        /** The transcript. [lostParts] pieces couldn't be sent (connection trouble). */
        fun onCloudFinished(text: String, lostParts: Int)
        /** Nothing usable. [silenced]: Android wouldn't let this app hear the mic at all. */
        fun onCloudFailed(message: String, silenced: Boolean)
    }

    private val url = prefs.workerUrl
    private val token = prefs.appToken
    private val language = prefs.language
    private val vocabulary = prefs.vocabularyList

    private val main = Handler(Looper.getMainLooper())
    private val uploader = Executors.newSingleThreadExecutor()
    private val texts = Collections.synchronizedList(mutableListOf<String>())
    private val lostParts = AtomicInteger(0)

    @Volatile private var running = false
    @Volatile private var cancelled = false
    @Volatile private var lastError = "no connection"

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
        Thread({ recordLoop(record) }, "VoiceBubble-recorder").start()
        return true
    }

    /** Stop recording and deliver the text. */
    fun stop() {
        running = false
    }

    /** Throw everything away without calling the listener. */
    fun cancel() {
        cancelled = true
        running = false
        uploader.shutdownNow()
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
                } else {
                    quietRun++
                    framesSinceSpeech++
                }

                val level = ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0).toFloat()
                main.post { if (running) listener.onMicLevel(level) }

                // Exact digital silence from the start means Android has muted this recording.
                if (!heardAnything && totalFrames >= SILENCED_AFTER_FRAMES) {
                    silenced = true
                    break
                }

                // Send a piece off at a pause, or at the size limit if you never pause.
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
            main.post { if (!cancelled) listener.onCloudFailed("Android muted the microphone", silenced = true) }
            return
        }
        if (autoStopped) main.post { if (!cancelled) listener.onCloudAutoStopped() }
        if (segmentHasSpeech) submit(segment.toByteArray())
        try {
            uploader.execute { finish() }
        } catch (e: Exception) {
            // Cancelled while finishing.
        }
    }

    private fun submit(pcm: ByteArray) {
        try {
            uploader.execute { upload(pcm) }
        } catch (e: Exception) {
            // Cancelled.
        }
    }

    /** Runs on the upload thread, one piece at a time, so the text stays in order. */
    private fun upload(pcm: ByteArray) {
        val wav = wavHeader(pcm.size) + pcm
        for (attempt in 1..ATTEMPTS) {
            if (cancelled) return
            try {
                val (code, body) = post(wav)
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
            try {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            } catch (e: InterruptedException) {
                return
            }
        }
        lostParts.incrementAndGet()
    }

    private fun finish() {
        val text = texts.toList().filter { it.isNotBlank() }.joinToString(" ")
        val lost = lostParts.get()
        main.post {
            if (cancelled) return@post
            if (text.isEmpty() && lost > 0) {
                listener.onCloudFailed(lastError, silenced = false)
            } else {
                listener.onCloudFinished(text, lost)
            }
        }
        uploader.shutdown()
    }

    private fun post(wav: ByteArray): Pair<Int, String> {
        val conn = URL("$url/transcribe").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(wav.size)
            conn.setRequestProperty("Content-Type", "audio/wav")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Language", language)
            if (vocabulary.isNotEmpty()) {
                val encoded = URLEncoder.encode(vocabulary.joinToString("\n"), "UTF-8").replace("+", "%20")
                conn.setRequestProperty("X-Vocabulary", encoded)
            }
            conn.outputStream.use { it.write(wav) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

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

        const val MIN_SEGMENT_FRAMES = 15 * FRAMES_PER_SECOND
        const val MAX_SEGMENT_FRAMES = 28 * FRAMES_PER_SECOND
        const val PAUSE_FRAMES = 20 // 0.4 s of quiet
        const val AUTO_STOP_SILENCE_FRAMES = 30 * FRAMES_PER_SECOND
        const val MAX_TOTAL_FRAMES = 5 * 60 * FRAMES_PER_SECOND
        const val SILENCED_AFTER_FRAMES = 75 // 1.5 s

        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 700L
    }
}
