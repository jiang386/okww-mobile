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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.config.ConfigRepo
import com.okww.mobile.config.KeyPoint
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
 * 悬浮控制台：在 Via 全屏游戏时随时开始/停止、切换任务开关，
 * 并可直接在游戏画面上「点按」添加按键，全程无需退出游戏。
 */
class OverlayService : Service() {

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var calView: FrameLayout? = null
    private var calHint: TextView? = null
    private val calMarkers = mutableListOf<TextView>()
    private val calKeys = KeyPoint.entries
    private var calIndex = 0

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
        removePanel()
        removeCalibration()
        App.instance.calibrating = false
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

    // ---------------------------------------------------------------- 悬浮面板

    private fun showPanel() {
        if (view != null) return
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
        v.findViewById<View>(R.id.btnOverlayCal).setOnClickListener { showCalibration() }
        v.findViewById<View>(R.id.btnOverlayCal).setOnLongClickListener {
            App.instance.cfg.clearPoints()
            ConfigRepo.save(this, App.instance.cfg)
            Toast.makeText(this, R.string.overlay_cal_cleared, Toast.LENGTH_SHORT).show()
            true
        }

        bindChips(v)

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

    private fun bindChips(v: View) {
        val chips = listOf(
            Chip(R.id.chipCloud, { App.instance.cfg.enabledCloudGuard }) { b -> App.instance.cfg.enabledCloudGuard = b },
            Chip(R.id.chipCombat, { App.instance.cfg.enabledCombat }) { b -> App.instance.cfg.enabledCombat = b },
            Chip(R.id.chipPickup, { App.instance.cfg.enabledPickup }) { b -> App.instance.cfg.enabledPickup = b },
            Chip(R.id.chipDialog, { App.instance.cfg.enabledDialog }) { b -> App.instance.cfg.enabledDialog = b },
            Chip(R.id.chipSwitch, { App.instance.cfg.enabledSwitch }) { b -> App.instance.cfg.enabledSwitch = b },
            Chip(R.id.chipLock, { App.instance.cfg.autoTarget }) { b -> App.instance.cfg.autoTarget = b }
        )
        chips.forEach { chip ->
            val tv = v.findViewById<TextView>(chip.id)
            tv.setOnClickListener {
                chip.set(!chip.get())
                ConfigRepo.save(this, App.instance.cfg)
                styleChip(tv, chip.get())
            }
            styleChip(tv, chip.get())
        }
    }

    private fun styleChip(tv: TextView, on: Boolean) {
        tv.setTextColor(ContextCompat.getColor(this, if (on) R.color.accent else R.color.muted))
        tv.alpha = if (on) 1f else 0.6f
    }

    private fun updateUi() {
        val v = view ?: return
        v.findViewById<TextView>(R.id.tvOverlayStatus).text = RunState.status.value
        v.findViewById<TextView>(R.id.btnOverlayToggle).text =
            if (RunState.running.value) getString(R.string.btn_stop) else getString(R.string.btn_start)
    }

    private fun removePanel() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Throwable) {
        }
        view = null
        params = null
    }

    // ---------------------------------------------------------------- 游戏内校准

    private fun showCalibration() {
        if (calView != null) {
            stopCalibration()
            return
        }
        if (RunState.running.value) App.instance.engine.stop()
        App.instance.calibrating = true
        RunState.setStatus(getString(R.string.cal_paused))
        removePanel()

        val root = LayoutInflater.from(this)
            .inflate(R.layout.overlay_calibration, null) as FrameLayout
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        calHint = root.findViewById(R.id.tvCalHint)
        root.findViewById<View>(R.id.btnCalDone).setOnClickListener { stopCalibration() }

        calIndex = firstUnsetIndex()
        updateCalHint()

        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    val key = calKeys[calIndex]
                    if (v.width > 0 && v.height > 0) {
                        App.instance.cfg.setPoint(key, e.x / v.width, e.y / v.height)
                        addMarker(v as FrameLayout, e.x, e.y, key)
                        calIndex = (calIndex + 1) % calKeys.size
                        updateCalHint()
                    }
                    true
                }

                else -> true
            }
        }

        wm?.addView(root, lp)
        calView = root
        Toast.makeText(this, R.string.cal_overlay_tip, Toast.LENGTH_LONG).show()
    }

    private fun stopCalibration() {
        removeCalibration()
        App.instance.calibrating = false
        ConfigRepo.save(this, App.instance.cfg)
        RunState.setStatus(getString(R.string.cal_saved))
        Toast.makeText(this, R.string.cal_saved, Toast.LENGTH_SHORT).show()
        showPanel()
    }

    private fun removeCalibration() {
        try {
            calView?.let { wm?.removeView(it) }
        } catch (_: Throwable) {
        }
        calView = null
        calHint = null
        calMarkers.clear()
    }

    private fun firstUnsetIndex(): Int {
        val idx = calKeys.indexOfFirst { !App.instance.cfg.isSet(it) }
        return if (idx >= 0) idx else 0
    }

    private fun updateCalHint() {
        val key = calKeys[calIndex]
        calHint?.text = getString(
            R.string.cal_overlay_hint,
            getString(key.labelRes),
            calIndex + 1,
            calKeys.size
        )
    }

    private fun addMarker(root: FrameLayout, x: Float, y: Float, key: KeyPoint) {
        calMarkers.filter { it.tag == key }.forEach {
            root.removeView(it)
            calMarkers.remove(it)
        }
        val tv = TextView(this).apply {
            text = shortLabel(key)
            textSize = 10f
            setTextColor(ContextCompat.getColor(this@OverlayService, R.color.accent))
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(4), dp(1), dp(4), dp(1))
            tag = key
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.leftMargin = (x - dp(10)).toInt().coerceAtLeast(0)
        lp.topMargin = (y - dp(10)).toInt().coerceAtLeast(0)
        root.addView(tv, lp)
        calMarkers.add(tv)
    }

    private fun shortLabel(k: KeyPoint): String = when (k) {
        KeyPoint.ATTACK -> "攻"
        KeyPoint.SKILL -> "技"
        KeyPoint.ULTIMATE -> "解"
        KeyPoint.ECHO -> "骸"
        KeyPoint.DODGE -> "闪"
        KeyPoint.JUMP -> "跳"
        KeyPoint.INTERACT -> "拾"
        KeyPoint.SWITCH_1 -> "1"
        KeyPoint.SWITCH_2 -> "2"
        KeyPoint.SWITCH_3 -> "3"
        KeyPoint.JOYSTICK -> "杆"
        KeyPoint.DIALOG_ADVANCE -> "话"
        KeyPoint.TARGET -> "锁"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 亮屏

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

    private data class Chip(
        val id: Int,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    )

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
