package com.evu.fireplace

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Animated fireplace. [intensity] (0..1) eases the flames up or down. */
class FireView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var intensity: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    private class Flame(val x: Float, val size: Float, val phase: Float)

    private val flames = listOf(
        Flame(0.50f, 1.00f, 0.0f),
        Flame(0.36f, 0.72f, 1.7f),
        Flame(0.64f, 0.78f, 3.1f),
        Flame(0.24f, 0.46f, 4.4f),
        Flame(0.76f, 0.50f, 5.6f),
    )
    // outer, middle, inner layers: color + size multiplier
    private val layerColors = intArrayOf(0xFFE8501A.toInt(), 0xFFFF9A1F.toInt(), 0xFFFFE066.toInt())
    private val layerScale = floatArrayOf(1.00f, 0.72f, 0.42f)

    private var shown = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A2B17.toInt() }
    private val path = Path()
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            shown += (intensity - shown) * 0.04f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glowPaint.shader = RadialGradient(
            w / 2f, h * 0.7f, w * 0.55f,
            intArrayOf(0x88FF7A1A.toInt(), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = SystemClock.uptimeMillis() / 1000f
        val baseY = h * 0.80f

        glowPaint.alpha = (255 * (0.15f + 0.85f * shown)).toInt()
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val alpha = (90 + 165 * shown).toInt()
        for (layer in 0..2) {
            paint.color = layerColors[layer]
            paint.alpha = alpha
            for (f in flames) {
                val flicker = 1f + 0.08f * sin(t * 7f + f.phase)
                val hf = h * 0.62f * f.size * layerScale[layer] * (0.12f + 0.88f * shown) * flicker
                val wf = hf * 0.45f
                val cx = w * f.x
                val sway = sin(t * 3f + f.phase) * wf * 0.25f
                path.reset()
                path.moveTo(cx, baseY)
                path.cubicTo(cx - wf, baseY, cx - wf * 0.8f + sway * 0.3f, baseY - hf * 0.5f, cx + sway, baseY - hf)
                path.cubicTo(cx + wf * 0.8f + sway * 0.3f, baseY - hf * 0.5f, cx + wf, baseY, cx, baseY)
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        // two crossed logs
        val logH = h * 0.06f
        for (angle in floatArrayOf(-7f, 7f)) {
            canvas.save()
            canvas.rotate(angle, w / 2f, baseY + logH)
            rect.set(w * 0.18f, baseY, w * 0.82f, baseY + logH * 1.6f)
            canvas.drawRoundRect(rect, logH, logH, logPaint)
            canvas.restore()
        }
    }
}
