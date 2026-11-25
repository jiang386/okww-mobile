package com.okww.mobile.engine.tasks

import android.os.SystemClock
import com.okww.mobile.config.KeyPoint
import com.okww.mobile.engine.OcrLine
import com.okww.mobile.engine.RunState
import com.okww.mobile.engine.VisionContext
import com.okww.mobile.service.Touch
import com.okww.mobile.util.LogBus
import java.util.Locale

/**
 * 云游戏守护：移植 OKWW 中的云游戏（浏览器串流）逻辑。
 * - 1020「云服务错误 / 服务器断开」弹窗 → 点退出/确定，随后重新进入；
 * - 长时间无操作被踢下线 → 自动重进；
 * - 排队 / 加载界面只等待，不误触；
 * - 角色全灭后的「复苏 / 重新挑战」。
 * 云端串流有延迟，同一动作 2.2s 内不重复点击。
 */
object CloudGuardTask {

    private var lastRun = 0L
    private var lastActionAt = 0L
    private var lastActionKey = ""

    private val DISCONNECT = listOf(
        "云服务错误", "服务器断开", "断开连接", "连接已断开", "网络异常",
        "连接超时", "与服务器断开", "错误码", "连接失败"
    )
    private val DISCONNECT_ACTIONS = listOf(
        "退出游戏", "重新连接", "重新登录", "重试", "刷新", "返回", "继续", "确定", "关闭", "取消"
    )
    private val ENTER = listOf(
        "进入游戏", "开始游戏", "点击进入", "立即进入", "开始冒险", "进入云", "点击屏幕", "登录"
    )
    private val ENTER_EXCLUDE = listOf("登录奖励", "退出登录", "切换账号", "登录记录")
    private val DEATH = listOf("复苏", "重新挑战", "返回起点", "复活", "恢复")
    private val QUEUE = listOf(
        "排队", "前方", "预计等待", "正在连接", "正在加载", "加载中", "正在进入", "正在初始化"
    )

    fun reset() {
        lastRun = 0L
        lastActionAt = 0L
        lastActionKey = ""
    }

    suspend fun run(vc: VisionContext, intervalMs: Long = 900L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < intervalMs) return false
        lastRun = now
        val lines = vc.ocr()
        if (lines.isEmpty()) return false
        val joined = lines.joinToString(" ") { it.text }

        if (joined.contains("密码") || joined.contains("验证码")) {
            RunState.setStatus("需要人工登录/验证")
            return true
        }
        val hasDisconnect = DISCONNECT.any { joined.contains(it) }
        if (QUEUE.any { joined.contains(it) } && !hasDisconnect) {
            RunState.setStatus("云游戏排队/加载中")
            return true
        }
        if (hasDisconnect) {
            RunState.setStatus("云断线重连中")
            val target = pick(lines, DISCONNECT_ACTIONS)
            val pt = target?.let { it.box.centerX() to it.box.centerY() } ?: (0.5f to 0.62f)
            act(pt, "disconnect", now)
            return true
        }
        val enter = lines.firstOrNull { l ->
            ENTER.any { l.text.contains(it) } && ENTER_EXCLUDE.none { l.text.contains(it) }
        }
        if (enter != null) {
            RunState.setStatus("进入云游戏")
            act(enter.box.centerX() to enter.box.centerY(), "enter", now)
            return true
        }
        val death = pick(lines, DEATH)
        if (death != null) {
            RunState.setStatus("角色阵亡处理")
            act(death.box.centerX() to death.box.centerY(), "death", now)
            return true
        }
        return false
    }

    private fun pick(lines: List<OcrLine>, keys: List<String>): OcrLine? =
        lines.firstOrNull { l -> keys.any { l.text.contains(it) } }

    private fun act(pt: Pair<Float, Float>, key: String, now: Long) {
        if (key == lastActionKey && now - lastActionAt < 2200L) return
        lastActionKey = key
        lastActionAt = now
        if (Touch.tapNorm(pt.first, pt.second)) {
            LogBus.i(
                "cloud",
                String.format(Locale.US, "点击 %s @ (%.2f, %.2f)", key, pt.first, pt.second)
            )
        }
    }
}

/** 剧情 / 对话：底部出现大段文字时推进，遇到「跳过」按钮优先点它。 */
object DialogTask {

    private var last = 0L
    private val MENU = listOf(
        "背包", "角色", "武器", "声骸", "设置", "任务", "地图", "合成", "商店",
        "图鉴", "活动", "邮件", "数据坞", "队伍", "烹饪", "锻造", "委托", "探索",
        "成就", "好友", "公告", "商城", "充值", "唤取", "共鸣者"
    )

    fun reset() {
        last = 0L
    }

    suspend fun run(vc: VisionContext): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 1500L) return false
        val lines = vc.ocrIn(0.08f, 0.62f, 0.92f, 0.97f)
        if (lines.isEmpty()) return false
        // 只认画面中下部的对话正文；左侧系统聊天、右上任务栏都会被排除。
        val main = lines.filter { it.box.centerX() in 0.28f..0.82f }
        if (main.isEmpty()) return false
        val joined = main.joinToString("") { it.text }
        val maxWidth = main.maxOf { it.box.width() }
        if (joined.length < 14 || maxWidth < 0.18f) return false
        if (MENU.any { joined.contains(it) }) return false
        last = now
        val skip = main.firstOrNull { it.text.contains("跳过") }
        if (skip != null) {
            Touch.tapNorm(skip.box.centerX(), skip.box.centerY())
            RunState.setStatus("跳过剧情")
        } else {
            val p = vc.cfg.p(KeyPoint.DIALOG_ADVANCE)
            Touch.tapNorm(p.x, p.y)
            RunState.setStatus("推进剧情")
        }
        return true
    }
}

/**
 * 自动拾取 / 交互：OCR 命中交互关键词后，直接点击该提示文字本身，
 * 避免固定坐标在不同机型上点偏（OKWW 使用 F 键 + 白名单/黑名单）。
 */
object PickupTask {

    private var last = 0L

    private val WHITE = listOf(
        "吸收", "拾取", "采集", "采矿", "调查", "开启", "交互", "对话",
        "共鸣", "阅读", "倾听", "使用", "乘坐", "垂钓", "追踪", "拍照"
    )
    private val BLACK = listOf(
        "开始合成", "领取奖励", "合成台", "开始烹饪", "开始锻造", "提交", "拆除",
        "确认", "取消", "购买", "出售", "强化", "调谐", "突破", "升级", "装备"
    )

    fun reset() {
        last = 0L
    }

    suspend fun run(vc: VisionContext): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 1100L) return false
        val lines = vc.ocrIn(0.40f, 0.25f, 0.99f, 0.85f)
        if (lines.isEmpty()) return false
        val hit = lines.firstOrNull { l ->
            val t = l.text
            t.length <= 10 && WHITE.any { t.contains(it) } && BLACK.none { t.contains(it) }
        } ?: return false
        last = now
        if (Touch.tapNorm(hit.box.centerX(), hit.box.centerY())) {
            LogBus.i("pick", "交互: ${hit.text}")
            RunState.setStatus("自动拾取/交互")
        }
        return true
    }
}

/**
 * 自动战斗：移植 OKWW CombatCheck + AutoCombatTask 的核心思路。
 * - 敌人/Boss 血条出现即进入战斗；血条消失后保留 3s 宽限（云串流闪烁）；
 * - 攻击持续点击，技能 / 解放 / 声骸 / 切人按可配置间隔轮转；
 * - 可在设置中开启「锁定敌人」定时点击。
 */
class CombatTask {

    var inCombat = false
        private set

    private var lastHealthAt = 0L
    private var lastAttack = 0L
    private var lastSkill = 0L
    private var lastUltimate = 0L
    private var lastEcho = 0L
    private var lastSwitch = 0L
    private var lastTarget = 0L
    private var switchIndex = 0

    fun reset() {
        inCombat = false
        lastHealthAt = 0L
        lastAttack = 0L
        lastSkill = 0L
        lastUltimate = 0L
        lastEcho = 0L
        lastSwitch = 0L
        lastTarget = 0L
        switchIndex = 0
    }

    /** 战斗任务关闭时也同步状态，避免拾取逻辑误判。 */
    fun track(hasHealth: Boolean) {
        if (!hasHealth) inCombat = false
    }

    fun run(vc: VisionContext) {
        val now = SystemClock.elapsedRealtime()
        val cfg = vc.cfg
        if (vc.analysis.inCombat) {
            if (!inCombat) {
                inCombat = true
                lastSkill = now - cfg.skillIntervalMs / 2
                lastUltimate = now
                lastEcho = now
                lastSwitch = now
                lastTarget = now
                LogBus.i("combat", "检测到战斗")
            }
            lastHealthAt = now
        } else if (inCombat && now - lastHealthAt > cfg.healthGraceMs) {
            inCombat = false
            RunState.setStatus("探索中")
            LogBus.i("combat", "战斗结束")
        }
        if (!inCombat) return

        RunState.setStatus("自动战斗中")
        if (now - lastAttack >= cfg.attackIntervalMs) {
            lastAttack = now
            tap(cfg, KeyPoint.ATTACK)
        }
        if (now - lastSkill >= cfg.skillIntervalMs) {
            lastSkill = now
            tap(cfg, KeyPoint.SKILL)
        }
        if (now - lastUltimate >= cfg.ultimateIntervalMs) {
            lastUltimate = now
            tap(cfg, KeyPoint.ULTIMATE)
        }
        if (now - lastEcho >= cfg.echoIntervalMs) {
            lastEcho = now
            tap(cfg, KeyPoint.ECHO)
        }
        if (cfg.enabledSwitch && now - lastSwitch >= cfg.switchIntervalMs) {
            lastSwitch = now
            val keys = arrayOf(KeyPoint.SWITCH_1, KeyPoint.SWITCH_2, KeyPoint.SWITCH_3)
            tap(cfg, keys[switchIndex % keys.size])
            switchIndex++
        }
        if (cfg.autoTarget && now - lastTarget >= cfg.targetIntervalMs) {
            lastTarget = now
            tap(cfg, KeyPoint.TARGET)
        }
    }

    private fun tap(cfg: com.okww.mobile.config.AutoConfig, key: KeyPoint) {
        val p = cfg.p(key)
        Touch.tapNorm(p.x, p.y)
    }
}
