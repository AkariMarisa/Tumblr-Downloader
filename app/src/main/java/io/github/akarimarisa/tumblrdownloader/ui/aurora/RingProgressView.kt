package io.github.akarimarisa.tumblrdownloader.ui.aurora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.akarimarisa.tumblrdownloader.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Circular progress ring: neutral track + brand-gradient arc starting at
 * 12 o'clock, round caps, stroke ≈13% of the view size (mockup ratio).
 *
 * `app:auroraProgress` is 0f..1f; the center percentage label is a
 * separate TextView in the Phase 1 layout. Not referenced by any layout
 * until Phase 1.
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.aurora_track)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var sweepShader: SweepGradient? = null

    /** 0f..1f; values outside are clamped. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.RingProgressView, defStyleAttr, 0)
            progress = a.getFloat(R.styleable.RingProgressView_auroraProgress, 0f)
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (96 * resources.displayMetrics.density).roundToInt()
        fun resolve(spec: Int): Int = when (MeasureSpec.getMode(spec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(spec)
            MeasureSpec.AT_MOST -> min(defaultSize, MeasureSpec.getSize(spec))
            else -> defaultSize
        }
        val w = resolve(widthMeasureSpec)
        setMeasuredDimension(w, resolve(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val cx = w / 2f
        val cy = h / 2f
        // SweepGradient starts at 3 o'clock; rotate so the arc starts at 12.
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(
                ContextCompat.getColor(context, R.color.aurora_gradient_start),
                ContextCompat.getColor(context, R.color.aurora_gradient_end)
            ),
            null
        )
        shader.setLocalMatrix(Matrix().apply { postRotate(-90f, cx, cy) })
        sweepShader = shader
        arcPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = min(w, h)
        val stroke = size * 0.132f
        val cx = w / 2f
        val cy = h / 2f
        val radius = size / 2f - stroke / 2f
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, radius, trackPaint)
        if (progress > 0f) {
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(bounds, -90f, progress * 360f, false, arcPaint)
        }
    }
}
