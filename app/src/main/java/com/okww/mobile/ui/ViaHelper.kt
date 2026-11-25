package com.okww.mobile.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import com.okww.mobile.util.LogBus
import com.okww.mobile.util.ScreenInfo

/** Via 浏览器（mark.via / mark.via.gp）相关辅助。 */
object ViaHelper {

    private val VIA_PACKAGES = listOf("mark.via", "mark.via.gp")

    fun findVia(ctx: Context): String? = VIA_PACKAGES.firstOrNull { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    fun openCloud(ctx: Context, url: String): Boolean {
        val pkg = findVia(ctx) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun adbGrantCommand(): String =
        "adb shell pm grant com.okww.mobile android.permission.WRITE_SECURE_SETTINGS"

    /** 鸣潮手机端（官方客户端 / B 服）包名。 */
    private val GAME_PACKAGES = listOf("com.kurogame.mingchao", "com.kurogame.mingchao.bilibili")

    fun findGame(ctx: Context): String? = GAME_PACKAGES.firstOrNull { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /** 启动已安装的鸣潮手机端；未安装时返回 false。 */
    fun openGame(ctx: Context): Boolean {
        val pkg = findGame(ctx) ?: return false
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}

/**
 * 「游戏模式」：横屏 + 全屏 + 免打扰 + 高亮度 + 亮屏。
 * - 横屏 / 亮度：需要系统的「修改系统设置」权限（可直接跳转授权，或用 adb 授 WRITE_SECURE_SETTINGS）；
 * - 沉浸全屏：需要 WRITE_SECURE_SETTINGS，通过 policy_control 对 Via 强制沉浸；
 * - 免打扰：需要在通知策略访问里允许本应用。
 */
object GameMode {

    private var savedAutoRotate: Int? = null
    private var savedBrightness: Int? = null
    private var savedBrightnessMode: Int? = null

    fun apply(ctx: Context): String {
        val sb = StringBuilder()
        val cr = ctx.contentResolver
        val canWrite = Settings.System.canWrite(ctx)
        if (!canWrite) {
            sb.append("· 未授予「修改系统设置」：横屏/亮度可能失败\n")
        }
        if (canWrite) {
            runCatching {
                if (savedAutoRotate == null) {
                    savedAutoRotate = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                    savedBrightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                    savedBrightnessMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                }
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                val target = if (ScreenInfo.rotation(ctx) == Surface.ROTATION_270) {
                    Surface.ROTATION_270
                } else {
                    Surface.ROTATION_90
                }
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, target)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255)
            }.onSuccess {
                sb.append("· 已锁定横屏并提高亮度\n")
            }.onFailure {
                sb.append("· 横屏/亮度设置失败：${it.message}\n")
            }
        }

        val immersive = runCatching {
            Settings.Global.putString(cr, "policy_control", "immersive.full=*")
            true
        }.getOrDefault(false)
        if (immersive) {
            sb.append("· 已开启全局沉浸全屏\n")
        } else {
            sb.append("· 沉浸全屏需一次 adb 授权（见下方命令）\n")
        }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            sb.append("· 免打扰已开启\n")
        } catch (t: Throwable) {
            sb.append("· 免打扰未授权（可在设置中允许通知策略访问）\n")
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            sb.append("· 建议把本应用加入电池优化白名单\n")
        }
        LogBus.i("game", "游戏模式已应用")
        return sb.toString()
    }

    fun restore(ctx: Context) {
        val cr = ctx.contentResolver
        runCatching { Settings.Global.putString(cr, "policy_control", "") }
        if (Settings.System.canWrite(ctx)) {
            runCatching {
                savedAutoRotate?.let { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, it) }
                savedBrightness?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it) }
                savedBrightnessMode?.let { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, it) }
            }
        }
        savedAutoRotate = null
        savedBrightness = null
        savedBrightnessMode = null
        LogBus.i("game", "游戏模式已还原")
    }

    fun openWriteSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
