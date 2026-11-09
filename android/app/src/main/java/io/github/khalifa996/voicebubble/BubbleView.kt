package io.github.khalifa996.voicebubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.hypot

/**
 * The floating mic: a round button that stays still, plus a small panel to its left while
 * you're talking with a cancel button and sound bars. Tap the mic to start or finish,
 * ✕ to throw the dictation away; drag the mic to move it.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : LinearLayout(context) {

    enum class Mode { IDLE, LISTENING, PROCESSING }

    var onTap: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragMove: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val bubbleSize = dp(52)
    private val shadowRoom = dp(8)

    /** Height of the overlay window, used to place it just above the keyboard. */
    val windowHeight: Int = bubbleSize + shadowRoom * 2

    private val circleBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val circle = FrameLayout(context)
    private val icon = ImageView(context)
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val panel = LinearLayout(context)
    private val cancel = FrameLayout(context)
    private val waveform = WaveformView(context)
    private var mode = Mode.IDLE

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom)
        setClipChildren(false)
        setClipToPadding(false)

        // Fixed size, so the window never resizes (and nothing shifts) while you talk.
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(PANEL_COLOR)
        }
        panel.elevation = dp(2).toFloat()
        panel.visibility = View.GONE

        val cross = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0.85f
        }
        cancel.addView(cross, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        cancel.contentDescription = context.getString(R.string.bubble_cancel)
        cancel.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onCancel?.invoke()
        }
        panel.addView(cancel, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(
            waveform,
            LinearLayout.LayoutParams(dp(88), dp(20)).apply { marginEnd = dp(14) },
        )
        addView(
            panel,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) },
        )

        circle.background = circleBackground
        circle.elevation = dp(4).toFloat()
        icon.setImageResource(R.drawable.ic_mic)
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
        circle.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        spinner.visibility = View.GONE
        circle.addView(spinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        addView(circle, LinearLayout.LayoutParams(bubbleSize, bubbleSize))

        setupTouch()
        setMode(Mode.IDLE)
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        when (newMode) {
            Mode.IDLE -> {
                circleBackground.setColor(IDLE_COLOR)
                icon.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                panel.visibility = View.GONE
                waveform.stop()
                circle.contentDescription = context.getString(R.string.bubble_start)
                alpha = 0.92f
            }
            Mode.LISTENING -> {
                circleBackground.setColor(LISTENING_COLOR)
                icon.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                panel.visibility = View.VISIBLE
                waveform.start()
                circle.contentDescription = context.getString(R.string.bubble_stop)
                alpha = 1f
            }
            Mode.PROCESSING -> {
                circleBackground.setColor(IDLE_COLOR)
                icon.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                panel.visibility = View.GONE
                waveform.stop()
                circle.contentDescription = context.getString(R.string.bubble_working)
                alpha = 1f
            }
        }
    }

    /** Real microphone loudness from [MicLevelMeter] (0 to 1, or -1 if muted). */
    fun setMicLevel(value: Float) {
        if (mode == Mode.LISTENING) waveform.pushMic(value)
    }

    /** The recogniser's own loudness reports, used if the mic meter can't hear. */
    fun setLevel(rmsDb: Float) {
        if (mode == Mode.LISTENING) waveform.pushLevel(rmsDb)
    }

    /** Words were just recognised: keep the bars up even if loudness reports are sparse. */
    fun onSpeech() {
        if (mode == Mode.LISTENING) waveform.pushSpeech()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var dragging = false
        circle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        onDragStart?.invoke()
                    }
                    if (dragging) onDragMove?.invoke(dx, dy)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (dragging) {
                        onDragEnd?.invoke()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (dragging) onDragEnd?.invoke()
                    true
                }
                else -> false
            }
        }
        // performClick() lands here, which also makes the bubble work with TalkBack.
        circle.setOnClickListener { onTap?.invoke() }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        val IDLE_COLOR = 0xEB1F1F1F.toInt()
        val LISTENING_COLOR = 0xFFE5484D.toInt()
        val PANEL_COLOR = 0xE6202124.toInt()
    }
}
