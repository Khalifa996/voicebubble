package io.github.khalifa996.voicebubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small strip of sound bars that scrolls right to left while you talk, so you can see
 * it's hearing you. The newest sound is on the right.
 *
 * It moves on a steady clock rather than waiting for the recogniser, because on-device
 * recognisers report loudness only now and then. Two signals keep the level up: loudness
 * reports ([pushLevel]) and words being recognised ([pushSpeech]). With neither, the level
 * falls back to a line of dots within about a second.
 */
class WaveformView(context: Context) : View(context) {

    private val targets = FloatArray(BAR_COUNT)
    private val shown = FloatArray(BAR_COUNT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val random = Random()

    private var level = 0f
    private var lastSignal = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            postDelayed(this, STEP_MS)
        }
    }

    /** Start scrolling from a flat line. */
    fun start() {
        reset()
        running = true
        removeCallbacks(tick)
        postDelayed(tick, STEP_MS)
    }

    /** Stop scrolling and go back to a flat line. */
    fun stop() {
        running = false
        removeCallbacks(tick)
        reset()
    }

    /** Loudness from the speech recogniser (roughly -2 to 10 dB). */
    fun pushLevel(rmsDb: Float) {
        val raw = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        // Square root lifts quiet speech so it's still visible.
        signal(sqrt(raw))
    }

    /** The recogniser just heard words, so you're definitely talking. */
    fun pushSpeech() = signal(SPEECH_LEVEL)

    private fun signal(value: Float) {
        level = max(level, value)
        lastSignal = SystemClock.uptimeMillis()
    }

    private fun step() {
        if (SystemClock.uptimeMillis() - lastSignal > HOLD_MS) level *= DECAY
        if (level < 0.02f) level = 0f
        // Vary each bar a little so speech looks like a waveform, not a solid block.
        val variation = 0.6f + random.nextFloat() * 0.4f
        System.arraycopy(targets, 1, targets, 0, BAR_COUNT - 1)
        targets[BAR_COUNT - 1] = level * variation
        postInvalidateOnAnimation()
    }

    private fun reset() {
        targets.fill(0f)
        shown.fill(0f)
        level = 0f
        lastSignal = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Bars and gaps the same width.
        val barWidth = w / (BAR_COUNT * 2 - 1)
        val radius = barWidth / 2f
        var moving = false

        for (i in 0 until BAR_COUNT) {
            val diff = targets[i] - shown[i]
            shown[i] += diff * EASE
            if (abs(diff) > 0.004f) moving = true

            val barHeight = max(barWidth, h * shown[i])
            val left = i * barWidth * 2f
            val top = (h - barHeight) / 2f
            rect.set(left, top, left + barWidth, top + barHeight)
            // Older sound (on the left) fades out a little.
            paint.alpha = 110 + (145 * i) / (BAR_COUNT - 1)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        if (moving) postInvalidateOnAnimation()
    }

    private companion object {
        const val BAR_COUNT = 18
        const val STEP_MS = 75L
        const val EASE = 0.45f
        const val SPEECH_LEVEL = 0.7f
        const val HOLD_MS = 350L
        const val DECAY = 0.8f
    }
}
