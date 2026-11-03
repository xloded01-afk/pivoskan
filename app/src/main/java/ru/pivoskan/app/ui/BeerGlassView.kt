package ru.pivoskan.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import ru.pivoskan.app.R
import kotlin.math.min

/** Decorative, offline Canvas glass; its one-shot fill respects system animator duration. */
class BeerGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var fill: Float = 0f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            invalidate()
        }

    @get:ColorInt
    @setparam:ColorInt
    var accentColor: Int = context.getColor(R.color.gold)
        set(value) {
            field = value
            if (drawingScale > 0f) updateBeerShader()
            invalidate()
        }

    private val beerColor = context.getColor(R.color.glass_beer)
    private val foamColor = context.getColor(R.color.glass_foam)
    private val highlightColor = context.getColor(R.color.glass_highlight)
    private val haloColor = context.getColor(R.color.gold_container)
    private val outlineColor = context.getColor(R.color.outline)
    private val surfaceColor = context.getColor(R.color.surface)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerGlass = Path()
    private val innerGlass = Path()
    private val highlight = Path()
    private var drawingScale = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var fillAnimator: ValueAnimator? = null
    private var animationStarted = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        drawingScale = min(availableWidth / VIEWPORT, availableHeight / VIEWPORT)
        if (drawingScale <= 0f) return
        offsetX = paddingLeft + (availableWidth - VIEWPORT * drawingScale) / 2f
        offsetY = paddingTop + (availableHeight - VIEWPORT * drawingScale) / 2f

        // All paths and gradients are cached in a 180-unit illustration viewport.
        outerGlass.apply {
            reset()
            moveTo(45f, 29f)
            lineTo(135f, 29f)
            lineTo(123f, 145f)
            quadTo(122f, 157f, 110f, 158f)
            lineTo(70f, 158f)
            quadTo(58f, 157f, 57f, 145f)
            close()
        }
        innerGlass.apply {
            reset()
            moveTo(51f, 36f)
            lineTo(129f, 36f)
            lineTo(118f, 144f)
            quadTo(117f, 151f, 109f, 152f)
            lineTo(71f, 152f)
            quadTo(63f, 151f, 62f, 144f)
            close()
        }
        highlight.apply {
            reset()
            moveTo(57f, 43f)
            lineTo(66f, 132f)
            moveTo(123f, 47f)
            lineTo(119f, 83f)
        }
        updateBeerShader()
    }

    private fun updateBeerShader() {
        beerPaint.shader = LinearGradient(
            56f, 44f, 124f, 154f,
            beerColor, ColorUtils.blendARGB(beerColor, accentColor, 0.4f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawingScale <= 0f) return
        val illustration = canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(drawingScale, drawingScale)

        paint.style = Paint.Style.FILL
        paint.color = haloColor
        paint.alpha = 95
        canvas.drawCircle(90f, 89f, 72f, paint)
        paint.color = outlineColor
        paint.alpha = 90
        canvas.drawOval(53f, 161f, 127f, 169f, paint)
        paint.color = surfaceColor
        paint.alpha = 145
        canvas.drawPath(outerGlass, paint)
        paint.alpha = 255

        val liquidTop = 152f - 116f * fill
        val clipped = canvas.save()
        canvas.clipPath(innerGlass)
        if (fill > 0f) {
            canvas.drawRect(47f, liquidTop, 133f, 158f, beerPaint)
            paint.color = foamColor
            paint.alpha = 120
            drawBubble(canvas, 75f, 131f, 2.3f, liquidTop)
            drawBubble(canvas, 103f, 112f, 2f, liquidTop)
            drawBubble(canvas, 87f, 93f, 1.6f, liquidTop)
            drawBubble(canvas, 111f, 141f, 1.6f, liquidTop)
            paint.alpha = 255
            canvas.drawRoundRect(46f, liquidTop - 5f, 134f, liquidTop + 8f, 6f, 6f, paint)
            canvas.drawCircle(59f, liquidTop - 2f, 7f, paint)
            canvas.drawCircle(75f, liquidTop - 4f, 9f, paint)
            canvas.drawCircle(94f, liquidTop - 3f, 8f, paint)
            canvas.drawCircle(113f, liquidTop - 2f, 7f, paint)
        }
        canvas.restoreToCount(clipped)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = ColorUtils.blendARGB(outlineColor, accentColor, 0.35f)
        canvas.drawPath(outerGlass, paint)
        canvas.drawOval(45f, 25f, 135f, 34f, paint)
        paint.color = highlightColor
        paint.strokeWidth = 3f
        canvas.drawPath(highlight, paint)
        paint.strokeWidth = 2f
        canvas.drawLine(72f, 149f, 108f, 149f, paint)
        canvas.restoreToCount(illustration)
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, radius: Float, liquidTop: Float) {
        if (y - radius > liquidTop + 11f) canvas.drawCircle(x, y, radius, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animationStarted) return
        animationStarted = true
        if (isInEditMode || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())) {
            fill = TARGET_FILL
            return
        }
        fillAnimator = ValueAnimator.ofFloat(fill, TARGET_FILL).apply {
            duration = 1_100L
            interpolator = DecelerateInterpolator()
            addUpdateListener { fill = it.animatedValue as Float }
            start()
        }
    }

    /** Rotation restores the completed illustration instead of replaying onboarding motion. */
    fun finishFillAnimation() {
        fillAnimator?.cancel()
        fillAnimator?.removeAllUpdateListeners()
        fillAnimator = null
        animationStarted = true
        fill = TARGET_FILL
    }

    override fun onDetachedFromWindow() {
        finishFillAnimation()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VIEWPORT = 180f
        const val TARGET_FILL = 0.82f
    }
}
