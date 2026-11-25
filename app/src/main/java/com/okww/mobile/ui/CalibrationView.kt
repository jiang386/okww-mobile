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

/**
 * 校准画布：画面 + 玩家自行添加的按键点。
 *
 * 交互规则（无默认键位）：
 *  - 点击空白处：把当前选中的按键放到点击位置，随后自动选中下一个未设置的按键；
 *  - 拖动已设置的点：微调位置；
 *  - 点击已设置的点：选中该点。
 */
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

    /** 点位增删改后回调，用于刷新底部按键选择条。 */
    var onPointsChanged: (() -> Unit)? = null

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
        selected = KeyPoint.entries.firstOrNull { !cfg.isSet(it) }
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        bg = bitmap
        invalidate()
    }

    /** 选中下一个尚未添加的按键；全部已添加时保持不变。 */
    fun selectNextUnset() {
        selected = KeyPoint.entries.firstOrNull { !cfg.isSet(it) } ?: selected
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bg?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        KeyPoint.entries.forEach { kp ->
            if (!cfg.isSet(kp)) return@forEach
            val p = cfg.p(kp) ?: return@forEach
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
                if (width <= 0 || height <= 0) return true
                // 1) 命中已设置的按键 → 选中并可拖动
                val hit = KeyPoint.entries
                    .filter { cfg.isSet(it) }
                    .mapNotNull { kp -> cfg.p(kp)?.let { kp to it } }
                    .minByOrNull { (_, p) ->
                        val dx = p.x * width - event.x
                        val dy = p.y * height - event.y
                        dx * dx + dy * dy
                    }
                if (hit != null) {
                    val (kp, p) = hit
                    val dx = p.x * width - event.x
                    val dy = p.y * height - event.y
                    if (dx * dx + dy * dy <= dp(34f) * dp(34f)) {
                        selected = kp
                        dragging = kp
                        return true
                    }
                }
                // 2) 空白处点击 → 放置当前选中的按键
                val key = selected ?: KeyPoint.entries.firstOrNull { !cfg.isSet(it) } ?: return true
                cfg.setPoint(key, event.x / width, event.y / height)
                dragging = key
                selected = nextUnset(key)
                onPointsChanged?.invoke()
                invalidate()
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
                if (dragging != null) onPointsChanged?.invoke()
                dragging = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nextUnset(from: KeyPoint): KeyPoint {
        val list = KeyPoint.entries
        val idx = list.indexOf(from)
        for (i in 1..list.size) {
            val k = list[(idx + i) % list.size]
            if (!cfg.isSet(k)) return k
        }
        return from
    }
}
