package io.github.khalifa996.voicebubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small strip of sound bars that scrolls right to left with the loudness of your
 * voice, so you can see it's hearing you. The newest sound is on the right.
 *
 * Bars ease towards their new height rather than snapping, so it moves smoothly.
 */
class WaveformView(context: Context) : View(context) {

    private val targets = FloatArray(BAR_COUNT)
    private val shown = FloatArray(BAR_COUNT)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    private var smoothed = 0f
    private var peakSinceStep = 0f
    private var lastStep = 0L

    /** Feed loudness from the speech recogniser (roughly -2 to 10 dB). */
    fun push(rmsDb: Float) {
        val raw = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        // Square root lifts quiet speech so it's still visible; smoothing takes out jitter.
        smoothed = smoothed * 0.55f + sqrt(raw) * 0.45f
        peakSinceStep = max(peakSinceStep, smoothed)

        val now = SystemClock.uptimeMillis()
        if (now - lastStep >= STEP_MS) {
            System.arraycopy(targets, 1, targets, 0, BAR_COUNT - 1)
            targets[BAR_COUNT - 1] = peakSinceStep
            peakSinceStep = 0f
            lastStep = now
        } else {
            targets[BAR_COUNT - 1] = max(targets[BAR_COUNT - 1], smoothed)
        }
        postInvalidateOnAnimation()
    }

    /** Back to a flat line of dots. */
    fun reset() {
        targets.fill(0f)
        shown.fill(0f)
        smoothed = 0f
        peakSinceStep = 0f
        lastStep = 0L
        invalidate()
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
        const val STEP_MS = 60L
        const val EASE = 0.35f
    }
}
