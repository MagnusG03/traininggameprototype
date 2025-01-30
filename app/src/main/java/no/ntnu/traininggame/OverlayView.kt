package no.ntnu.traininggame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A custom view that draws pose landmarks as red circles.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    @Volatile
    private var landmarks: List<Pair<Float, Float>> = emptyList()

    private val circlePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Updates the landmark list (x, y in [0..1]) and requests a redraw.
     */
    fun setLandmarks(newLandmarks: List<Pair<Float, Float>>) {
        landmarks = newLandmarks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthScale = width.toFloat()
        val heightScale = height.toFloat()

        for ((x, y) in landmarks) {
            val drawX = x * widthScale
            val drawY = y * heightScale
            canvas.drawCircle(drawX, drawY, 10f, circlePaint)
        }
    }
}
