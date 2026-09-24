package com.example.animatedsplash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class EditorLineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private var lineCount = 1
    private var lineHeight = (22 * resources.displayMetrics.density).toInt()
    private var verticalScroll = 0
    private val editorTopPadding = (16 * resources.displayMetrics.density).toInt()

    init {
        paint.color = ContextCompat.getColor(context, R.color.editor_gutter_text)
        setBackgroundColor(ContextCompat.getColor(context, R.color.editor_gutter))
        isFocusable = false
    }

    fun update(lineCount: Int, lineHeight: Int, scrollY: Int) {
        this.lineCount = lineCount.coerceAtLeast(1)
        this.lineHeight = lineHeight.coerceAtLeast(1)
        this.verticalScroll = scrollY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val firstLine = (verticalScroll / lineHeight).coerceAtLeast(0)
        val offset = -(verticalScroll % lineHeight)
        val visibleLines = height / lineHeight + 2
        val right = width - 10f * resources.displayMetrics.density
        for (index in 0 until visibleLines) {
            val line = firstLine + index + 1
            if (line > lineCount) break
            val baseline = editorTopPadding + offset + (index + 1) * lineHeight - 5f * resources.displayMetrics.density
            canvas.drawText(line.toString(), right, baseline, paint)
        }
    }
}
