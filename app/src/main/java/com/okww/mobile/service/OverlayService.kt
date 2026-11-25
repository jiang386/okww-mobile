package com.okww.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.engine.RunState
import com.okww.mobile.ui.MainActivity
import com.okww.mobile.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 悬浮控制台：在 Via 全屏游戏时也能随时开始/停止自动化，并保持亮屏。
 */
class OverlayService : Service() {

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundSafe()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showPanel()
        acquireWakeLock()
        scope.launch {
            combine(RunState.running, RunState.status) { running, status -> running to status }
                .collect { updateUi() }
        }
        LogBus.i("overlay", "悬浮控制台已显示")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Throwable) {
        }
        view = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, App.CH_OVERLAY)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, 0)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun showPanel() {
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 24
        lp.y = 24

        v.findViewById<View>(R.id.btnOverlayToggle).setOnClickListener {
            if (RunState.running.value) {
                App.instance.engine.stop()
            } else if (!App.instance.engine.start()) {
                Toast.makeText(this, RunState.status.value, Toast.LENGTH_SHORT).show()
            }
        }
        v.findViewById<View>(R.id.btnOverlayClose).setOnClickListener { stopSelf() }

        val status = v.findViewById<TextView>(R.id.tvOverlayStatus)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        status.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    try {
                        wm?.updateViewLayout(v, lp)
                    } catch (_: Throwable) {
                    }
                    true
                }

                else -> false
            }
        }

        wm?.addView(v, lp)
        view = v
        params = lp
        updateUi()
    }

    private fun updateUi() {
        val v = view ?: return
        v.findViewById<TextView>(R.id.tvOverlayStatus).text = RunState.status.value
        v.findViewById<TextView>(R.id.btnOverlayToggle).text =
            if (RunState.running.value) getString(R.string.btn_stop) else getString(R.string.btn_start)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "okww:game"
        ).apply {
            setReferenceCounted(false)
            try {
                acquire(6 * 60 * 60 * 1000L)
            } catch (t: Throwable) {
                LogBus.w("overlay", "保持亮屏失败: ${t.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    companion object {
        @Volatile
        var instance: OverlayService? = null
            private set

        private const val NOTIF_ID = 20

        fun show(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun hide(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
