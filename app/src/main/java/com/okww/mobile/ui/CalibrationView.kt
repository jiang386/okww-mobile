package com.okww.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.okww.mobile.R
import com.okww.mobile.config.AutoConfig
import com.okww.mobile.config.KeyPoint

/** 校准画布：画面 + 可拖拽的按键点。 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cfg: AutoConfig = AutoConfig()

    var selected: KeyPoint? = null
        set(value) {
            field = value
            invalidate()
        }

    private var bg: Bitmap? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }

    private var dragging: KeyPoint? = null

    init {
        fill.color = ContextCompat.getColor(context, R.color.accent)
        labelPaint.isSubpixelText = true
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        bg = bitmap
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bg?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        KeyPoint.entries.forEach { kp ->
            val p = cfg.p(kp)
            val x = p.x * width
            val y = p.y * height
            val isSel = kp == selected
            fill.alpha = if (isSel) 235 else 165
            canvas.drawCircle(x, y, dp(if (isSel) 13f else 10f), fill)
            canvas.drawCircle(x, y, dp(if (isSel) 18f else 14f), ring)
            val text = context.getString(kp.labelRes)
            val tw = labelPaint.measureText(text)
            canvas.drawRoundRect(
                RectF(x + dp(8f), y - dp(10f), x + dp(16f) + tw, y + dp(8f)),
                dp(4f), dp(4f), labelBg
            )
            canvas.drawText(text, x + dp(12f), y + dp(4f), labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = KeyPoint.entries.minByOrNull { kp ->
                    val p = cfg.p(kp)
                    val dx = p.x * width - event.x
                    val dy = p.y * height - event.y
                    dx * dx + dy * dy
                } ?: return false
                val p = cfg.p(hit)
                val dx = p.x * width - event.x
                val dy = p.y * height - event.y
                if (dx * dx + dy * dy <= dp(34f) * dp(34f)) {
                    selected = hit
                    dragging = hit
                    return true
                }
                selected = null
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val kp = dragging ?: return true
                if (width > 0 && height > 0) {
                    cfg.setPoint(kp, event.x / width, event.y / height)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
