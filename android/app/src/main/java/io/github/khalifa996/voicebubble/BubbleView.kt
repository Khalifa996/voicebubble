package io.github.khalifa996.voicebubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
import android.widget.TextView
import kotlin.math.hypot

/**
 * The floating mic: a round button, plus a pill to its left that shows the words as you
 * speak. Tap to start or stop; drag to move it.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context) : LinearLayout(context) {

    enum class Mode { IDLE, LISTENING, PROCESSING }

    var onTap: (() -> Unit)? = null
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
    private val preview = TextView(context)
    private var mode = Mode.IDLE

    init {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(shadowRoom, shadowRoom, shadowRoom, shadowRoom)
        setClipChildren(false)
        setClipToPadding(false)

        preview.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxWidth = dp(260)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(PANEL_COLOR)
            }
            visibility = View.GONE
        }
        addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) },
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
                preview.visibility = View.GONE
                preview.text = ""
                resetScale()
                circle.contentDescription = context.getString(R.string.bubble_start)
                alpha = 0.92f
            }
            Mode.LISTENING -> {
                circleBackground.setColor(LISTENING_COLOR)
                icon.visibility = View.VISIBLE
                spinner.visibility = View.GONE
                circle.contentDescription = context.getString(R.string.bubble_stop)
                alpha = 1f
            }
            Mode.PROCESSING -> {
                circleBackground.setColor(IDLE_COLOR)
                icon.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                resetScale()
                circle.contentDescription = context.getString(R.string.bubble_working)
                alpha = 1f
            }
        }
    }

    fun showPreview(text: String) {
        if (mode != Mode.LISTENING) return
        preview.text = text
        preview.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /** Pulse the button with the loudness of your voice so you can see it's hearing you. */
    fun setLevel(rmsDb: Float) {
        if (mode != Mode.LISTENING) return
        val level = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        val scale = 1f + level * 0.15f
        circle.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
    }

    private fun resetScale() {
        circle.animate().cancel()
        circle.scaleX = 1f
        circle.scaleY = 1f
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
