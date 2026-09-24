package com.example.animatedsplash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Lightweight Arduino Serial Plotter style view. */
class SerialChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val channels = mutableListOf<MutableList<Float>>()
    private val channelNames = mutableListOf<String>()
    private val channelColors = intArrayOf(
        Color.rgb(76, 175, 80),
        Color.rgb(255, 152, 0),
        Color.rgb(33, 150, 243),
        Color.rgb(156, 39, 176),
        Color.rgb(244, 67, 54),
        Color.rgb(0, 150, 136)
    )
    private var plotting = true
    private var interpolate = true

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 221, 227)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(118, 124, 136)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 97, 108)
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private val legendBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plotRect = RectF()

    fun setValues(newValues: List<Float>) {
        clearValues()
        appendValues(newValues)
    }

    fun appendValue(value: Float) = appendValues(listOf(value))

    /** Adds one serial sample. Multiple numbers on one line are plotted as separate channels. */
    fun appendValues(values: List<Float>) {
        if (!plotting || values.isEmpty()) return
        ensureChannels(values.size)
        for (index in channels.indices) {
            channels[index].add(if (index < values.size) values[index] else Float.NaN)
            if (channels[index].size > MAX_POINTS) channels[index].removeAt(0)
        }
        invalidate()
    }

    fun clearValues() {
        channels.clear()
        invalidate()
    }

    fun setPlotting(enabled: Boolean) {
        plotting = enabled
        invalidate()
    }

    fun isPlotting(): Boolean = plotting

    fun setInterpolate(enabled: Boolean) {
        interpolate = enabled
        invalidate()
    }

    fun isInterpolate(): Boolean = interpolate

    fun channelCount(): Int = channels.size

    fun channelName(index: Int): String = channelNames.getOrNull(index)
        ?.takeIf { it.isNotBlank() }
        ?: "var ${index + 1}"

    fun setChannelNames(names: List<String>) {
        ensureChannels(max(names.size, channels.size))
        channelNames.clear()
        channels.indices.forEach { index ->
            channelNames += names.getOrNull(index).orEmpty().ifBlank { "var ${index + 1}" }
        }
        invalidate()
    }

    private fun ensureChannels(count: Int) {
        while (channels.size < count) channels.add(mutableListOf())
        while (channelNames.size < channels.size) channelNames += "var ${channelNames.size + 1}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val left = 56f
        val top = 38f
        val right = (width - 12).toFloat()
        val bottom = (height - 34).toFloat()
        if (right <= left || bottom <= top) return
        plotRect.set(left, top, right, bottom)

        val finiteValues = channels.flatMap { channel -> channel.filter { it.isFinite() } }
        val minValue = finiteValues.minOrNull() ?: 0f
        val maxValue = finiteValues.maxOrNull() ?: 1f
        val range = max(maxValue - minValue, 1f)
        val scaleMin = if (finiteValues.isEmpty()) 0f else minValue - range * 0.05f
        val scaleMax = if (finiteValues.isEmpty()) 1f else maxValue + range * 0.05f
        val scaleRange = max(scaleMax - scaleMin, 1f)

        drawGrid(canvas, left, top, right, bottom, scaleMin, scaleMax)
        drawChannels(canvas, left, top, right, bottom, scaleMin, scaleRange)
        drawLegend(canvas)

        if (channels.isEmpty()) {
            labelPaint.color = Color.rgb(120, 125, 135)
            canvas.drawText("Waiting for numeric serial data…", left + 12f, top + 28f, labelPaint)
        }
        if (!plotting) {
            labelPaint.color = Color.rgb(180, 80, 30)
            canvas.drawText("STOPPED", right - 58f, top - 12f, labelPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, minValue: Float, maxValue: Float) {
        for (row in 0..5) {
            val y = top + (bottom - top) * row / 5f
            canvas.drawLine(left, y, right, y, if (row == 5) axisPaint else gridPaint)
            val value = maxValue - (maxValue - minValue) * row / 5f
            canvas.drawText(formatValue(value), 8f, y + 4f, labelPaint)
        }
        val columns = 8
        for (column in 0..columns) {
            val x = left + (right - left) * column / columns.toFloat()
            canvas.drawLine(x, top, x, bottom, if (column == 0) axisPaint else gridPaint)
            if (column < columns) canvas.drawText("${column * MAX_POINTS / columns}", x + 2f, bottom + 21f, labelPaint)
        }
    }

    private fun drawChannels(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, minValue: Float, range: Float) {
        channels.forEachIndexed { channelIndex, values ->
            if (values.isEmpty()) return@forEachIndexed
            linePaint.color = channelColors[channelIndex % channelColors.size]
            val path = Path()
            var started = false
            values.forEachIndexed { pointIndex, value ->
                if (!value.isFinite()) {
                    started = false
                    return@forEachIndexed
                }
                val x = if (values.size <= 1) right else left + (right - left) * pointIndex / (values.size - 1).toFloat()
                val normalized = ((value - minValue) / range).coerceIn(0f, 1f)
                val y = bottom - normalized * (bottom - top)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else if (interpolate && pointIndex >= 2) {
                    val previousX = left + (right - left) * (pointIndex - 1) / (values.size - 1).toFloat()
                    val previousY = path.currentPointY(y)
                    val control = (previousX + x) / 2f
                    path.cubicTo(control, previousY, control, y, x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, linePaint)
        }
    }

    private fun drawLegend(canvas: Canvas) {
        var x = 62f
        channels.forEachIndexed { index, _ ->
            val color = channelColors[index % channelColors.size]
            legendBoxPaint.color = color
            canvas.drawRoundRect(RectF(x, 13f, x + 10f, 23f), 2f, 2f, legendBoxPaint)
            legendPaint.color = color
            val label = channelName(index)
            canvas.drawText(label, x + 15f, 22f, legendPaint)
            x += max(64f, legendPaint.measureText(label) + 26f)
        }
    }

    private fun formatValue(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else String.format("%.2f", value)

    // Path has no public last-point getter; use a simple value estimate for smooth cubic segments.
    private fun Path.currentPointY(fallback: Float): Float = fallback

    companion object {
        private const val MAX_POINTS = 500
    }
}
