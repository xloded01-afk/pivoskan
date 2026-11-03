package ru.pivoskan.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import androidx.core.content.ContextCompat
import ru.pivoskan.app.R
import ru.pivoskan.app.data.City
import ru.pivoskan.app.data.Store

/** Decorative, deterministic offline schematic. It represents no geographic coordinates. */
class CityMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val river = Path()
    private val park = RectF()
    private val labelRect = RectF()
    private val pin = Path().apply {
        moveTo(0f, 10f)
        cubicTo(-3f, 6f, -8f, 0f, -8f, -5f)
        cubicTo(-8f, -15f, 8f, -15f, 8f, -5f)
        cubicTo(8f, 0f, 3f, 6f, 0f, 10f)
        close()
    }
    private val blockColor: Int
    private val roadColor: Int
    private val parkColor: Int
    private val riverColor: Int
    private val labelColor = color(R.color.map_label)
    private val metroColor = color(R.color.map_metro)
    private val inkColor = color(R.color.map_pin_ink)
    private val surfaceColor = color(R.color.surface)
    private val mainTextColor = color(R.color.text_primary)
    private var markerColor = color(R.color.gold)
    private var streetOne = context.getString(R.string.details_map_street_fallback)
    private var streetTwo = streetOne
    private var storeLabel = context.getString(R.string.details_map_store_fallback)
    private val metroLabel = context.getString(R.string.details_map_metro)
    private val demoLabel = context.getString(R.string.details_map_canvas_demo)
    private var streetAngle = -12f
    private var roadOffset = 0f
    private var markerX = 200f
    private var markerY = 110f
    private var metroX = 62f
    private var metroY = 124f
    private var markerProgress = 1f
    private var markerAnimator: ValueAnimator? = null
    private var hasData = false

    init {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.CityMapView, defStyleAttr, 0)
        try {
            blockColor = attributes.getColor(R.styleable.CityMapView_mapBlock, color(R.color.map_block))
            roadColor = attributes.getColor(R.styleable.CityMapView_mapRoad, color(R.color.map_road))
            parkColor = attributes.getColor(R.styleable.CityMapView_mapPark, color(R.color.map_park))
            riverColor = attributes.getColor(R.styleable.CityMapView_mapRiver, color(R.color.map_river))
        } finally {
            attributes.recycle()
        }
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // A preview also has a valid background and geometry, even before bind().
        buildGeography(0L)
        contentDescription = context.getString(R.string.details_map_demo)
    }

    fun bind(city: City, store: Store, address: String) {
        val citySeed = seed(city.id)
        val storeSeed = seed("${city.id}|${store.id}")
        buildGeography(citySeed)
        markerX = 112f + (storeSeed % 139).toFloat()
        // Below the demo badge, above the address overlay (also at large font sizes).
        markerY = 104f + ((storeSeed / 139) % 16).toFloat()
        markerColor = store.color
        storeLabel = store.shortName.ifBlank { store.name }
        val streets = city.streets
        streetOne = streets.getOrNull((citySeed % streets.size.coerceAtLeast(1)).toInt())
            ?: context.getString(R.string.details_map_street_fallback)
        streetTwo = if (streets.isEmpty()) streetOne else {
            streets[((citySeed + 1) % streets.size).toInt()]
        }
        contentDescription = context.getString(
            R.string.details_map_accessibility, city.name, store.name, address
        )
        hasData = true
        if (isAttachedToWindow) animateMarker() else markerProgress = 0f
        invalidate()
    }

    private fun buildGeography(citySeed: Long) {
        val bend = (citySeed % 31).toFloat()
        river.reset()
        river.moveTo(278f + bend, -25f)
        river.cubicTo(202f, 44f, 355f, 97f, 276f - bend, 158f)
        river.cubicTo(245f, 184f, 272f, 205f, 243f, 245f)
        val parkX = 28f + (citySeed % 35).toFloat()
        park.set(parkX, 58f, parkX + 68f, 113f)
        streetAngle = -9f - (citySeed % 7).toFloat()
        roadOffset = (citySeed % 19).toFloat()
        metroX = parkX + 15f
        metroY = 133f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val checkpoint = canvas.save()
        // Reference coordinates keep all geometry bounded at narrow and wide sizes.
        canvas.scale(width / MAP_WIDTH, height / MAP_HEIGHT)
        canvas.clipRect(0f, 0f, MAP_WIDTH, MAP_HEIGHT)
        canvas.drawColor(blockColor)
        paint.style = Paint.Style.FILL
        paint.color = parkColor
        canvas.drawRoundRect(park, 14f, 14f, paint)

        val grid = canvas.save()
        canvas.rotate(streetAngle, MAP_WIDTH / 2, MAP_HEIGHT / 2)
        paint.color = roadColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f
        for (i in -2..9) {
            val x = i * 51f + roadOffset
            canvas.drawLine(x, -100f, x, 320f, paint)
        }
        for (i in -2..7) {
            val y = i * 49f + roadOffset
            canvas.drawLine(-100f, y, 460f, y, paint)
        }
        paint.strokeWidth = 15f
        canvas.drawLine(-100f, 137f, 460f, 137f, paint)
        canvas.restoreToCount(grid)

        paint.color = riverColor
        paint.strokeWidth = 27f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(river, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL

        textPaint.color = labelColor
        textPaint.textSize = 10f * resources.configuration.fontScale.coerceIn(1f, 1.4f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ellipsize(streetOne, 176f), 18f, 30f, textPaint)
        canvas.drawText(ellipsize(streetTwo, 184f), 143f, 150f, textPaint)
        // Keep the demo status on the Canvas itself, including when it is shown on its own.
        labelRect.set(210f, 12f, 348f, 39f)
        paint.color = surfaceColor
        canvas.drawRoundRect(labelRect, 7f, 7f, paint)
        textPaint.color = mainTextColor
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(ellipsize(demoLabel, 126f), 279f,
            25.5f - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        paint.color = surfaceColor
        canvas.drawCircle(metroX, metroY, 11f, paint)
        textPaint.color = metroColor
        textPaint.textSize = 12f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(metroLabel, metroX, metroY + 4f, textPaint)

        drawMarker(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawMarker(canvas: Canvas) {
        val progress = markerProgress.coerceIn(0f, 1f)
        if (progress <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(markerX, markerY)
        val scale = 0.8f + 0.2f * progress
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.color = surfaceColor
        paint.alpha = (255 * progress).toInt()
        canvas.drawCircle(0f, 0f, 24f, paint)
        paint.color = markerColor
        paint.alpha = (255 * progress).toInt()
        canvas.drawCircle(0f, 0f, 20f, paint)
        paint.color = inkColor
        paint.alpha = (255 * progress).toInt()
        canvas.drawPath(pin, paint)
        paint.color = markerColor
        paint.alpha = (255 * progress).toInt()
        canvas.drawCircle(0f, -5f, 3f, paint)
        canvas.restoreToCount(checkpoint)

        textPaint.textSize = 11f * resources.configuration.fontScale.coerceIn(1f, 1.4f)
        textPaint.textAlign = Paint.Align.CENTER
        val label = ellipsize(storeLabel, 176f)
        val labelWidth = textPaint.measureText(label) + 20f
        val labelCenter = markerX.coerceIn(labelWidth / 2 + 8f, MAP_WIDTH - labelWidth / 2 - 8f)
        val labelBottom = markerY - 30f
        val labelHeight = textPaint.fontMetrics.let { it.descent - it.ascent } + 12f
        labelRect.set(labelCenter - labelWidth / 2, labelBottom - labelHeight,
            labelCenter + labelWidth / 2, labelBottom)
        paint.color = surfaceColor
        paint.alpha = (255 * progress).toInt()
        canvas.drawRoundRect(labelRect, 7f, 7f, paint)
        textPaint.color = mainTextColor
        textPaint.alpha = (255 * progress).toInt()
        canvas.drawText(label, labelCenter, labelBottom - 6f - textPaint.fontMetrics.descent, textPaint)
        paint.alpha = 255
        textPaint.alpha = 255
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasData) animateMarker()
    }

    private fun animateMarker() {
        stopAnimator()
        if (isInEditMode || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())) {
            markerProgress = 1f
            invalidate()
            return
        }
        markerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                markerProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        markerProgress = 1f
        super.onDetachedFromWindow()
    }

    private fun stopAnimator() {
        markerAnimator?.removeAllUpdateListeners()
        markerAnimator?.cancel()
        markerAnimator = null
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }

    private fun ellipsize(text: String, maxWidth: Float): String =
        TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun seed(value: String): Long {
        var result = 0x811C9DC5L
        value.toByteArray(Charsets.UTF_8).forEach {
            result = ((result xor (it.toLong() and 0xFFL)) * 0x01000193L) and 0xFFFFFFFFL
        }
        return result
    }

    private companion object {
        const val MAP_WIDTH = 360f
        const val MAP_HEIGHT = 220f
    }
}
