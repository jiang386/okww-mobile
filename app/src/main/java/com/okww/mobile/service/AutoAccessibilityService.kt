package com.okww.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.okww.mobile.util.LogBus
import com.okww.mobile.util.ScreenInfo

/**
 * 无障碍手势服务：云游戏画面是网页 canvas / 视频流，无法用控件 API 点击，
 * 只能通过无障碍手势注入真实的触摸事件。
 */
class AutoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogBus.i("access", "无障碍服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        LogBus.w("access", "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float, durationMs: Long = 55L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1.5f, y + 1.5f)
        }
        return dispatch(path, durationMs)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 700L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1.5f, y + 1.5f)
        }
        return dispatch(path, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, durationMs)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(20L))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) {
            LogBus.w("access", "手势失败: ${t.message}")
            false
        }
    }

    companion object {
        @Volatile
        var instance: AutoAccessibilityService? = null
            private set

        fun ready(): Boolean = instance != null
    }
}

/** 归一化坐标 -> 真实屏幕坐标的点击封装。 */
object Touch {

    fun tapNorm(nx: Float, ny: Float, durationMs: Long = 55L): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val size = ScreenInfo.size(svc)
        return svc.tap(nx.coerceIn(0f, 1f) * size[0], ny.coerceIn(0f, 1f) * size[1], durationMs)
    }

    fun longPressNorm(nx: Float, ny: Float, durationMs: Long = 700L): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val size = ScreenInfo.size(svc)
        return svc.longPress(nx.coerceIn(0f, 1f) * size[0], ny.coerceIn(0f, 1f) * size[1], durationMs)
    }

    /** 从摇杆位置向指定方向持续拖动，可按需用于走位。 */
    fun moveNorm(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 900L): Boolean {
        val svc = AutoAccessibilityService.instance ?: return false
        val size = ScreenInfo.size(svc)
        return svc.swipe(
            fromX.coerceIn(0f, 1f) * size[0],
            fromY.coerceIn(0f, 1f) * size[1],
            toX.coerceIn(0f, 1f) * size[0],
            toY.coerceIn(0f, 1f) * size[1],
            durationMs
        )
    }

    fun back(): Boolean = AutoAccessibilityService.instance?.back() ?: false
}
