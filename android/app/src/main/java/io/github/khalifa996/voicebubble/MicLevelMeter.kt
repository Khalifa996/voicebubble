package io.github.khalifa996.voicebubble

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures how loud the microphone is, about 50 times a second, purely to draw the sound
 * bars. The audio is never stored or sent anywhere; the speech recogniser listens separately.
 *
 * Start it *before* the recogniser: Android gives the microphone to whichever recording
 * started last, so the recogniser always wins and dictation is never affected. If Android
 * mutes this meter as a result, it reads exact silence and reports -1, and the bars fall
 * back to the recogniser's own signals.
 *
 * [onLevel] gets 0 (silence) to 1 (loud), or -1 when the meter is muted, on the main thread.
 */
class MicLevelMeter(private val onLevel: (Float) -> Unit) {

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    /** Returns false if the microphone couldn't be opened; nothing else to do in that case. */
    @SuppressLint("MissingPermission") // Checked by the caller before dictation starts.
    fun start(): Boolean {
        if (running) return true
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
                .setBufferSizeInBytes(max(minBuffer, SAMPLE_RATE / 5 * 2))
                .setPrivacySensitive(false)
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
        Thread({ readLoop(record) }, "VoiceBubble-meter").start()
        return true
    }

    fun stop() {
        running = false
    }

    private fun readLoop(record: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 50) // 20 ms
        try {
            while (running) {
                val count = record.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) continue
                var sum = 0.0
                for (i in 0 until count) {
                    val sample = buffer[i].toDouble()
                    sum += sample * sample
                }
                val level = if (sum == 0.0) {
                    -1f // Exact digital silence: Android has muted this recording.
                } else {
                    val rms = sqrt(sum / count) / 32768.0
                    val db = 20.0 * log10(rms)
                    ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0).toFloat()
                }
                main.post { if (running) onLevel(level) }
            }
        } catch (e: Exception) {
            // Mic went away; the bars fall back to the recogniser's signals.
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                // Already stopped.
            }
            record.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // Volume range mapped onto the bars, in dB below full scale.
        const val QUIET_DB = -55.0
        const val LOUD_DB = -15.0
    }
}
