package com.evu.fireplace

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Live chart of the last five minutes: power (filled orange) and chip stress (yellow line),
 * both on a 0-100% scale, with a dashed line at the level's chip-stress target. The newest
 * second is always at the right edge, so a new run fills in from the right.
 */
class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var samples: List<Sample> = emptyList()
    private var target = 0f

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFF3E6
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFB347.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF7A1A
        style = Paint.Style.FILL
    }
    private val powerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF7A1A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        strokeJoin = Paint.Join.ROUND
    }
    private val stressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE066.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC9A98C.toInt()
        textSize = 11f * sp
    }
    private val line = Path()
    private val area = Path()

    /** Hands the chart a fresh list of samples (oldest first) and the target to draw. */
    fun submit(newSamples: List<Sample>, targetHeadroom: Float) {
        samples = newSamples
        target = targetHeadroom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 36f * d
        val right = w - 8f * d
        val top = 8f * d
        val bottom = h - 20f * d
        if (right <= left || bottom <= top) return

        fun y(v: Float) = bottom - (bottom - top) * v.coerceIn(0f, 1f)

        // grid and left labels
        for (frac in floatArrayOf(0f, 0.5f, 1f)) {
            canvas.drawLine(left, y(frac), right, y(frac), gridPaint)
            canvas.drawText("${(frac * 100).toInt()}%", 2f * d, y(frac) + 4f * d, textPaint)
        }
        canvas.drawText("5 min ago", left, h - 4f * d, textPaint)
        val now = "now"
        canvas.drawText(now, right - textPaint.measureText(now), h - 4f * d, textPaint)

        if (samples.size < 2) {
            val hint = "The chart fills in once the fire is lit"
            canvas.drawText(hint, (w - textPaint.measureText(hint)) / 2f, (top + bottom) / 2f, textPaint)
            return
        }

        val n = samples.size
        val step = (right - left) / (HeatState.HISTORY_SIZE - 1)
        fun x(i: Int) = right - (n - 1 - i) * step

        // target line
        if (target > 0f) canvas.drawLine(left, y(target), right, y(target), targetPaint)

        // power: filled area plus outline
        area.reset()
        line.reset()
        area.moveTo(x(0), bottom)
        for (i in 0 until n) {
            val py = y(samples[i].duty)
            area.lineTo(x(i), py)
            if (i == 0) line.moveTo(x(i), py) else line.lineTo(x(i), py)
        }
        area.lineTo(x(n - 1), bottom)
        area.close()
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, powerPaint)

        // chip stress: a line that breaks wherever the phone gave no reading
        line.reset()
        var pen = false
        for (i in 0 until n) {
            val hr = samples[i].headroom
            if (hr.isNaN()) {
                pen = false
                continue
            }
            if (pen) line.lineTo(x(i), y(hr)) else line.moveTo(x(i), y(hr))
            pen = true
        }
        canvas.drawPath(line, stressPaint)

        // legend
        var lx = left + 6f * d
        val ly = top + 12f * d
        textPaint.color = 0xFFFF7A1A.toInt()
        canvas.drawText("Power", lx, ly, textPaint)
        lx += textPaint.measureText("Power") + 12f * d
        textPaint.color = 0xFFFFE066.toInt()
        canvas.drawText("Chip stress", lx, ly, textPaint)
        lx += textPaint.measureText("Chip stress") + 12f * d
        textPaint.color = 0xFFFFB347.toInt()
        canvas.drawText("- - target", lx, ly, textPaint)
        textPaint.color = 0xFFC9A98C.toInt()
    }
}