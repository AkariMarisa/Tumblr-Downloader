package io.github.akarimarisa.tumblrdownloader.ui.aurora

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import io.github.akarimarisa.tumblrdownloader.R

/**
 * The Aurora Glass screen background: solid root color + two soft radial
 * blobs (violet top-right, pink bottom-left), drawn to the exact fractions
 * of the design mockups (docs/design/svg/, 312×692 reference screen).
 *
 * Not referenced by any layout until the Phase 1 atomic switch.
 */
class AuroraBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rootColor = context.getColor(R.color.aurora_root)
    private val violetInner = context.getColor(R.color.aurora_blob_violet_inner)
    private val violetOuter = context.getColor(R.color.aurora_blob_violet_outer)
    private val pinkInner = context.getColor(R.color.aurora_blob_pink_inner)
    private val pinkOuter = context.getColor(R.color.aurora_blob_pink_outer)

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var violetShader: Shader? = null
    private var pinkShader: Shader? = null

    private val isDark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        // Mockup blobs: r=228 of a 312-wide screen (×1.05 in dark),
        // centers at (300,42) and (10,702).
        val radius = w * (if (isDark) 0.7665f else 0.73f)
        violetShader = RadialGradient(
            w * 0.9615f, h * 0.0607f, radius,
            intArrayOf(violetInner, violetOuter, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        pinkShader = RadialGradient(
            w * 0.032f, h * 1.0145f, radius,
            intArrayOf(pinkInner, pinkOuter, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(rootColor)
        val w = width.toFloat()
        val h = height.toFloat()
        violetShader?.let {
            blobPaint.shader = it
            canvas.drawRect(0f, 0f, w, h, blobPaint)
        }
        pinkShader?.let {
            blobPaint.shader = it
            canvas.drawRect(0f, 0f, w, h, blobPaint)
        }
    }
}
