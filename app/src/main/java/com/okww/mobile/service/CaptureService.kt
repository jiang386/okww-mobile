package com.okww.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.engine.Frame
import com.okww.mobile.ui.MainActivity
import com.okww.mobile.util.LogBus
import com.okww.mobile.util.ScreenInfo

/**
 * 屏幕采集服务：MediaProjection + VirtualDisplay + ImageReader。
 * 云端游戏画面来自 Via 的网页串流，必须实时采集像素才能做血条/OCR 判定。
 * 采集分辨率按长边压缩到 1280，兼顾速度与识别率；旋转时自动重建。
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var displayManager: DisplayManager? = null
    private var lastFrameAt = 0L
    private var lastSize = 0

    @Volatile
    var frame: Frame? = null
        private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            LogBus.w("capture", "投屏已结束")
            cleanup()
            stopSelf()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handler?.post { startDisplay() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        thread = HandlerThread("okww-capture").apply { start() }
        handler = Handler(thread!!.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafe()
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null && code != 0) {
                    startProjection(code, data)
                } else {
                    LogBus.w("capture", "投屏授权数据无效")
                }
            }

            ACTION_STOP -> {
                cleanup()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        thread?.quitSafely()
        thread = null
        handler = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CH_CAPTURE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_capture_title))
            .setContentText(getString(R.string.notif_capture_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        releaseDisplay()
        releaseProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            LogBus.e("capture", "获取投屏失败: ${t.message}")
            null
        } ?: return
        projection = mp
        mp.registerCallback(projectionCallback, handler)
        startDisplay()
        LogBus.i("capture", "投屏采集已开始")
    }

    private fun startDisplay() {
        val mp = projection ?: return
        val real = ScreenInfo.size(this)
        val longSide = maxOf(real[0], real[1])
        val scale = if (longSide > 1280) 1280f / longSide else 1f
        var w = ((real[0] * scale).toInt() / 2) * 2
        var h = ((real[1] * scale).toInt() / 2) * 2
        w = w.coerceAtLeast(2)
        h = h.coerceAtLeast(2)
        if (display != null && w * h == lastSize) return

        releaseDisplay()
        lastSize = w * h
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = ir
        ir.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 100L) return@setOnImageAvailableListener
                lastFrameAt = now
                frame = Frame(toBitmap(image, w, h), now)
            } catch (t: Throwable) {
                LogBus.w("capture", "帧处理失败: ${t.message}")
            } finally {
                image.close()
            }
        }, handler)

        val density = resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: DisplayMetrics.DENSITY_DEFAULT
        display = mp.createVirtualDisplay(
            "okww-capture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler
        )
        LogBus.i("capture", "采集分辨率 ${w}x${h}")
    }

    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytes = w * pixelStride
        return if (rowStride == rowBytes) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
        } else {
            val codedW = (rowStride / pixelStride).coerceAtLeast(w)
            val padded = Bitmap.createBitmap(codedW, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val out = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            out
        }
    }

    private fun releaseDisplay() {
        try {
            display?.release()
        } catch (_: Throwable) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
    }

    private fun releaseProjection() {
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        frame = null
        lastSize = 0
    }

    private fun cleanup() {
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        releaseDisplay()
        releaseProjection()
    }

    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set

        private const val ACTION_START = "com.okww.mobile.action.START_CAPTURE"
        private const val ACTION_STOP = "com.okww.mobile.action.STOP_CAPTURE"
        private const val EXTRA_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"
        private const val NOTIF_ID = 10

        fun requestStart(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun requestStop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java).apply { action = ACTION_STOP })
        }
    }
}
