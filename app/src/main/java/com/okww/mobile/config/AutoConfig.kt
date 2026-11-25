package com.okww.mobile.config

import android.content.Context
import android.graphics.PointF
import com.okww.mobile.R
import org.json.JSONObject

/**
 * 需要校准的触控点。坐标均为相对屏幕的 0~1 归一化值，
 * 不同分辨率 / 不同云游戏 UI 只需校准一次。
 *
 * 默认值参考移动端《云·鸣潮》横屏布局，可能与实际机型存在偏差，务必校准。
 */
enum class KeyPoint(val labelRes: Int, val defX: Float, val defY: Float) {
    ATTACK(R.string.point_attack, 0.928f, 0.712f),
    SKILL(R.string.point_skill, 0.763f, 0.778f),
    ULTIMATE(R.string.point_ultimate, 0.725f, 0.867f),
    ECHO(R.string.point_echo, 0.806f, 0.673f),
    DODGE(R.string.point_dodge, 0.870f, 0.678f),
    JUMP(R.string.point_jump, 0.871f, 0.842f),
    INTERACT(R.string.point_interact, 0.855f, 0.430f),
    SWITCH_1(R.string.point_switch1, 0.950f, 0.322f),
    SWITCH_2(R.string.point_switch2, 0.950f, 0.433f),
    SWITCH_3(R.string.point_switch3, 0.950f, 0.544f),
    JOYSTICK(R.string.point_joystick, 0.130f, 0.670f),
    DIALOG_ADVANCE(R.string.point_dialog, 0.800f, 0.780f),
    TARGET(R.string.point_target, 0.945f, 0.600f)
}

class AutoConfig {

    val points = LinkedHashMap<KeyPoint, PointF>()

    var cloudUrl: String = DEFAULT_CLOUD_URL
    var enabledCloudGuard: Boolean = true
    var enabledCombat: Boolean = true
    var enabledPickup: Boolean = true
    var enabledDialog: Boolean = true
    var enabledSwitch: Boolean = true
    var autoTarget: Boolean = false

    var attackIntervalMs: Long = 320L
    var skillIntervalMs: Long = 1600L
    var ultimateIntervalMs: Long = 6000L
    var echoIntervalMs: Long = 9000L
    var switchIntervalMs: Long = 15000L
    var targetIntervalMs: Long = 3000L

    /** 血条消失后仍判定为战斗中的宽限时间（云游戏串流闪烁，参考 OKWW 的 3s）。 */
    var healthGraceMs: Long = 3000L

    init {
        resetPoints()
    }

    fun resetPoints() {
        points.clear()
        KeyPoint.entries.forEach { points[it] = PointF(it.defX, it.defY) }
    }

    fun p(key: KeyPoint): PointF = points[key] ?: PointF(key.defX, key.defY)

    fun setPoint(key: KeyPoint, x: Float, y: Float) {
        points[key] = PointF(x.coerceIn(0.01f, 0.99f), y.coerceIn(0.01f, 0.99f))
    }

    fun toJson(): String {
        val root = JSONObject()
        root.put("cloudUrl", cloudUrl)
        root.put("cloud", enabledCloudGuard)
        root.put("combat", enabledCombat)
        root.put("pickup", enabledPickup)
        root.put("dialog", enabledDialog)
        root.put("switch", enabledSwitch)
        root.put("target", autoTarget)
        root.put("attackInterval", attackIntervalMs)
        root.put("skillInterval", skillIntervalMs)
        root.put("ultimateInterval", ultimateIntervalMs)
        root.put("echoInterval", echoIntervalMs)
        root.put("switchInterval", switchIntervalMs)
        root.put("healthGrace", healthGraceMs)
        val pts = JSONObject()
        points.forEach { (k, v) ->
            val o = JSONObject()
            o.put("x", v.x.toDouble())
            o.put("y", v.y.toDouble())
            pts.put(k.name, o)
        }
        root.put("points", pts)
        return root.toString()
    }

    companion object {
        const val DEFAULT_CLOUD_URL = "https://mc.kurogames.com/cloud/"

        fun fromJson(text: String?): AutoConfig {
            val cfg = AutoConfig()
            if (text.isNullOrBlank()) return cfg
            runCatching {
                val root = JSONObject(text)
                cfg.cloudUrl = root.optString("cloudUrl", DEFAULT_CLOUD_URL)
                cfg.enabledCloudGuard = root.optBoolean("cloud", true)
                cfg.enabledCombat = root.optBoolean("combat", true)
                cfg.enabledPickup = root.optBoolean("pickup", true)
                cfg.enabledDialog = root.optBoolean("dialog", true)
                cfg.enabledSwitch = root.optBoolean("switch", true)
                cfg.autoTarget = root.optBoolean("target", false)
                cfg.attackIntervalMs = root.optLong("attackInterval", cfg.attackIntervalMs)
                cfg.skillIntervalMs = root.optLong("skillInterval", cfg.skillIntervalMs)
                cfg.ultimateIntervalMs = root.optLong("ultimateInterval", cfg.ultimateIntervalMs)
                cfg.echoIntervalMs = root.optLong("echoInterval", cfg.echoIntervalMs)
                cfg.switchIntervalMs = root.optLong("switchInterval", cfg.switchIntervalMs)
                cfg.healthGraceMs = root.optLong("healthGrace", cfg.healthGraceMs)
                val pts = root.optJSONObject("points")
                if (pts != null) {
                    KeyPoint.entries.forEach { k ->
                        val o = pts.optJSONObject(k.name) ?: return@forEach
                        cfg.setPoint(k, o.optDouble("x", k.defX.toDouble()).toFloat(), o.optDouble("y", k.defY.toDouble()).toFloat())
                    }
                }
            }
            return cfg
        }
    }
}

object ConfigRepo {

    private const val PREFS = "okww_config"
    private const val KEY = "config_json"

    fun load(ctx: Context): AutoConfig =
        AutoConfig.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(ctx: Context, cfg: AutoConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, cfg.toJson()).apply()
    }
}
