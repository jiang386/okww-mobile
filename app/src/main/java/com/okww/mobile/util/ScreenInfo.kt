package com.okww.mobile.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.WindowManager

object ScreenInfo {

    /** 当前旋转方向下真实屏幕尺寸（px）。 */
    fun size(ctx: Context): IntArray {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            intArrayOf(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            intArrayOf(p.x, p.y)
        }
    }

    fun rotation(ctx: Context): Int {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.rotation
    }

    fun refreshRate(ctx: Context): Float {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        return wm.defaultDisplay.refreshRate
    }

    fun isLandscape(ctx: Context): Boolean {
        val s = size(ctx)
        return s[0] >= s[1]
    }

    fun displayId(): Int = Display.DEFAULT_DISPLAY
}
