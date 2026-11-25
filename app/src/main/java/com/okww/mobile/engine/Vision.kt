package com.okww.mobile.engine

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class Frame(val bitmap: Bitmap, val at: Long)

/** 识别出的一行文字。box 使用 0~1 归一化坐标，方便直接映射到任意分辨率。 */
data class OcrLine(val text: String, val box: RectF)

class Analysis(val inCombat: Boolean, val dark: Boolean)

/**
 * 文字识别：云游戏画面（网页串流）里的弹窗、按钮与剧情文字都是像素，
 * 因此用离线中文 OCR 判断「云服务错误 / 进入游戏 / 复活」等状态，
 * 比按固定模板匹配更耐受不同机型与串流压缩。
 */
object Ocr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var lastAt = 0L
    private var lastLines: List<OcrLine> = emptyList()
    private var busy = false

    suspend fun lines(frame: Frame, intervalMs: Long = 700L): List<OcrLine> {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastAt < intervalMs) return lastLines
        busy = true
        return try {
            val src = frame.bitmap
            val scaled = if (src.width > 960) {
                val w = 960
                val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(2)
                Bitmap.createScaledBitmap(src, w, h, true)
            } else src
            val result = recognize(scaled)
            if (scaled !== src) scaled.recycle()
            lastLines = result
            lastAt = now
            result
        } catch (t: Throwable) {
            lastLines
        } finally {
            busy = false
        }
    }

    fun reset() {
        lastAt = 0L
        lastLines = emptyList()
    }

    private suspend fun recognize(bmp: Bitmap): List<OcrLine> {
        val w = bmp.width
        val h = bmp.height
        return suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    val out = ArrayList<OcrLine>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val b = line.boundingBox ?: continue
                            val t = line.text.trim()
                            if (t.isEmpty()) continue
                            out.add(
                                OcrLine(
                                    t,
                                    RectF(
                                        b.left.toFloat() / w,
                                        b.top.toFloat() / h,
                                        b.right.toFloat() / w,
                                        b.bottom.toFloat() / h
                                    )
                                )
                            )
                        }
                    }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(emptyList())
                }
        }
    }
}

/**
 * 画面判定。移植自 OKWW 的 CombatCheck：
 * - 敌人血条：横向红色条带（云端压缩后颜色会漂移，所以放宽了 RGB 区间）；
 * - Boss 血条：顶部中央的亮红条；
 * - 黑屏/加载中：整体亮度极低。
 */
object Vision {

    private var pixels: IntArray? = null

    fun analyze(frame: Frame): Analysis {
        val bmp = frame.bitmap
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return Analysis(false, true)

        var buf = pixels
        if (buf == null || buf.size != w * h) {
            buf = IntArray(w * h)
            pixels = buf
        }
        val arr: IntArray = buf
        bmp.getPixels(arr, 0, w, 0, 0, w, h)

        // 暗屏检测
        var darkCount = 0
        var samples = 0
        var sy = 0
        while (sy < h) {
            var sx = 0
            while (sx < w) {
                val c = arr[sy * w + sx]
                val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                if (lum < 28) darkCount++
                samples++
                sx += 16
            }
            sy += 16
        }
        if (samples > 0 && darkCount.toFloat() / samples > 0.88f) return Analysis(false, true)

        // Boss 血条：顶部中央
        var bossHits = 0
        var bossTotal = 0
        val by0 = (h * 0.018f).toInt()
        val by1 = (h * 0.105f).toInt()
        val bx0 = (w * 0.32f).toInt()
        val bx1 = (w * 0.68f).toInt()
        var y = by0
        while (y <= by1) {
            var x = bx0
            while (x <= bx1) {
                if (isBossRed(arr[y * w + x])) bossHits++
                bossTotal++
                x += 2
            }
            y += 2
        }
        if (bossTotal > 0 && bossHits.toFloat() / bossTotal > 0.006f) return Analysis(true, false)

        // 普通敌人血条：中央区域内的横向红色细条
        var streak = 0
        var row = (h * 0.14f).toInt()
        val endY = (h * 0.86f).toInt()
        val x0 = (w * 0.16f).toInt()
        val x1 = (w * 0.84f).toInt()
        while (row < endY) {
            var run = 0
            var best = 0
            var x = x0
            while (x < x1) {
                if (isEnemyRed(arr[row * w + x])) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
                x += 2
            }
            if (best * 2 >= w * 0.045f) {
                streak++
                if (streak >= 2) return Analysis(true, false)
            } else {
                streak = 0
            }
            row += 2
        }
        return Analysis(false, false)
    }

    private fun isEnemyRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r in 150..240 && g in 35..110 && b in 35..105 && r - g >= 55 && r - b >= 50
    }

    private fun isBossRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 222 && g in 20..190 && b <= 95 && r - g >= 55
    }
}
