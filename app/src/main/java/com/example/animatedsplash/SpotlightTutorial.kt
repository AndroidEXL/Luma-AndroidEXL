package com.example.animatedsplash

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton

data class TutorialStep(
    val anchor: View,
    val title: String,
    val message: String
)

class SpotlightTutorial(
    private val activity: Activity,
    private val steps: List<TutorialStep>,
    private val onFinished: (completed: Boolean) -> Unit,
    private val onLastStep: (() -> Unit)? = null,
    private val hostRoot: ViewGroup? = null,
    private val onStepExplained: ((TutorialStep, Int, Int) -> Unit)? = null
) {
    private var currentStep = 0
    private lateinit var overlay: TutorialOverlayView

    fun start() {
        if (steps.isEmpty()) {
            onFinished(true)
            return
        }
        val root = hostRoot ?: activity.findViewById<ViewGroup>(android.R.id.content)
        overlay = TutorialOverlayView(activity)
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.skipButton.setOnClickListener { close(false) }
        overlay.nextButton.setOnClickListener {
            if (currentStep == steps.lastIndex) {
                onStepExplained?.invoke(steps[currentStep], currentStep + 1, steps.size)
                if (onLastStep != null) close(false, onLastStep) else close(true)
            } else {
                onStepExplained?.invoke(steps[currentStep], currentStep + 1, steps.size)
                showStep(currentStep + 1)
            }
        }
        showStep(0)
    }

    private fun showStep(index: Int) {
        currentStep = index
        val step = steps[index]
        overlay.title.text = step.title
        overlay.message.text = step.message
        overlay.counter.text = activity.getString(R.string.tutorial_step_counter, index + 1, steps.size)
        overlay.nextButton.text = activity.getString(if (index == steps.lastIndex) R.string.tutorial_finish else R.string.tutorial_next)
        overlay.setTarget(step.anchor)
        overlay.card.alpha = 0f
        overlay.card.animate().alpha(1f).setDuration(180L).start()
    }

    private fun close(completed: Boolean, afterClose: (() -> Unit)? = null) {
        overlay.animate().alpha(0f).setDuration(160L).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            onFinished(completed)
            afterClose?.invoke()
        }.start()
    }
}

private class TutorialOverlayView(activity: Activity) : FrameLayout(activity) {
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(198, 11, 20, 42) }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        color = Color.rgb(255, 188, 62)
    }
    private val targetRect = RectF()
    private var target: View? = null
    private var pulse = 0f

    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(activity.getColor(R.color.toolbar_surface))
        }
        elevation = dp(12).toFloat()
    }
    val counter = TextView(activity).apply { setTextColor(Color.rgb(93, 92, 235)); textSize = 11f; letterSpacing = 0.12f }
    val title = TextView(activity).apply { setTextColor(activity.getColor(R.color.text_primary)); textSize = 21f; setPadding(0, dp(8), 0, 0) }
    val message = TextView(activity).apply { setTextColor(activity.getColor(R.color.text_secondary)); textSize = 14f; setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, dp(8), 0, 0) }
    val skipButton = MaterialButton(activity).apply { text = activity.getString(R.string.tutorial_skip); isAllCaps = false }
    val nextButton = MaterialButton(activity).apply { isAllCaps = false }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        card.addView(counter)
        card.addView(title)
        card.addView(message)
        val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        actions.addView(skipButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(nextButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        card.addView(actions)
        addView(card, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(28)
        })
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun setTarget(view: View) {
        target = view
        post { updateTargetRect(); invalidate() }
    }

    private fun updateTargetRect() {
        val rect = android.graphics.Rect()
        if (target?.getGlobalVisibleRect(rect) == true) {
            val own = IntArray(2)
            getLocationOnScreen(own)
            val pad = dp(8)
            targetRect.set(
                (rect.left - own[0] - pad).toFloat(),
                (rect.top - own[1] - pad).toFloat(),
                (rect.right - own[0] + pad).toFloat(),
                (rect.bottom - own[1] + pad).toFloat()
            )
            val targetIsLow = targetRect.centerY() > height / 2f
            (card.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (targetIsLow) Gravity.TOP else Gravity.BOTTOM
                topMargin = if (targetIsLow) dp(28) else 0
                bottomMargin = if (targetIsLow) 0 else dp(28)
            }.also { card.layoutParams = it }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (!targetRect.isEmpty) {
            canvas.drawRoundRect(targetRect, dp(18).toFloat(), dp(18).toFloat(), clearPaint)
            ringPaint.alpha = (150 + pulse * 105).toInt()
            val expansion = dp(2) + pulse * dp(6)
            canvas.drawRoundRect(RectF(targetRect).apply { inset(-expansion, -expansion) }, dp(20).toFloat(), dp(20).toFloat(), ringPaint)
        }
        canvas.restore()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
