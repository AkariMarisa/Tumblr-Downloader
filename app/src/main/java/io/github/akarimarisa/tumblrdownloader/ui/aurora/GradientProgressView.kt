package io.github.akarimarisa.tumblrdownloader.ui.aurora

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.akarimarisa.tumblrdownloader.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Horizontal progress bar: neutral track + brand-gradient fill, stadium
 * shaped (radius = height/2), default height 8dp.
 *
 * `app:auroraProgress` is 0f..1f. Not referenced by any layout until Phase 1.
 */
class GradientProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradientStart = ContextCompat.getColor(context, R.color.aurora_gradient_start)
    private val gradientEnd = ContextCompat.getColor(context, R.color.aurora_gradient_end)

    private var fillShader: Shader? = null
    private var lastShaderWidth = -1f

    /** 0f..1f; values outside are clamped. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.aurora_track)
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.GradientProgressView, defStyleAttr, 0)
            progress = a.getFloat(R.styleable.GradientProgressView_auroraProgress, 0f)
            trackPaint.color = a.getColor(
                R.styleable.GradientProgressView_auroraTrackColor,
                trackPaint.color
            )
            a.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultH = (8 * resources.displayMetrics.density).roundToInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(defaultH, MeasureSpec.getSize(heightMeasureSpec))
            else -> defaultH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        canvas.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)

        val fillWidth = w * progress
        if (fillWidth <= 0f) return
        if (fillShader == null || abs(fillWidth - lastShaderWidth) > 0.5f) {
            fillShader = LinearGradient(
                0f, 0f, fillWidth, 0f,
                gradientStart, gradientEnd,
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = fillShader
            lastShaderWidth = fillWidth
        }
        canvas.drawRoundRect(0f, 0f, fillWidth, h, r, r, fillPaint)
    }
}
