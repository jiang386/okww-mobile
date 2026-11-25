package com.okww.mobile.engine

import android.content.Context
import android.os.SystemClock
import com.okww.mobile.App
import com.okww.mobile.config.AutoConfig
import com.okww.mobile.config.KeyPoint
import com.okww.mobile.engine.tasks.CloudGuardTask
import com.okww.mobile.engine.tasks.CombatTask
import com.okww.mobile.engine.tasks.DialogTask
import com.okww.mobile.engine.tasks.PickupTask
import com.okww.mobile.service.AutoAccessibilityService
import com.okww.mobile.service.CaptureService
import com.okww.mobile.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object RunState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("空闲")

    fun setStatus(text: String) {
        if (status.value != text) status.value = text
    }
}

class VisionContext(val frame: Frame, val cfg: AutoConfig, val analysis: Analysis) {

    private var cached: List<OcrLine>? = null

    suspend fun ocr(): List<OcrLine> {
        cached?.let { return it }
        val res = Ocr.lines(frame)
        cached = res
        return res
    }

    suspend fun ocrIn(left: Float, top: Float, right: Float, bottom: Float): List<OcrLine> =
        ocr().filter {
            it.box.right >= left && it.box.left <= right && it.box.bottom >= top && it.box.top <= bottom
        }
}

class AutomationEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val combat = CombatTask()

    fun start(): Boolean {
        if (RunState.running.value) return true
        if (CaptureService.instance?.frame == null) {
            RunState.setStatus("请先完成投屏授权")
            return false
        }
        if (!AutoAccessibilityService.ready()) {
            RunState.setStatus("请先开启无障碍服务")
            return false
        }
        val cfg = App.instance.cfg
        if (cfg.enabledCombat && !cfg.isSet(KeyPoint.ATTACK)) {
            RunState.setStatus("请先在「按键校准」点按添加按键")
            return false
        }
        CloudGuardTask.reset()
        DialogTask.reset()
        PickupTask.reset()
        combat.reset()
        Ocr.reset()
        job = scope.launch { loop() }
        RunState.running.value = true
        RunState.setStatus("运行中")
        LogBus.i("engine", "自动化已启动")
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        if (RunState.running.value) LogBus.i("engine", "自动化已停止")
        RunState.running.value = false
        RunState.setStatus("已停止")
        combat.reset()
        Ocr.reset()
    }

    private suspend fun loop() {
        var lastTick = 0L
        while (currentCoroutineContext().isActive) {
            val frame = CaptureService.instance?.frame
            if (frame == null) {
                RunState.setStatus("等待投屏")
                delay(300)
                continue
            }
            if (!AutoAccessibilityService.ready()) {
                RunState.setStatus("等待无障碍服务")
                delay(500)
                continue
            }
            if (App.instance.uiVisible) {
                RunState.setStatus("控制台前台，已暂停")
                delay(400)
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick < 120) {
                delay(50)
                continue
            }
            lastTick = now

            val cfg = App.instance.cfg
            val analysis = Vision.analyze(frame)
            val vc = VisionContext(frame, cfg, analysis)
            try {
                if (cfg.enabledCloudGuard &&
                    CloudGuardTask.run(vc, if (combat.inCombat) 2200L else 900L)
                ) {
                    delay(120)
                    continue
                }
                if (analysis.dark) {
                    RunState.setStatus("画面加载中")
                    delay(250)
                    continue
                }
                if (cfg.enabledDialog && DialogTask.run(vc)) {
                    delay(120)
                    continue
                }
                if (cfg.enabledPickup && !combat.inCombat) {
                    PickupTask.run(vc)
                }
                if (cfg.enabledCombat) {
                    combat.run(vc)
                } else {
                    combat.track(analysis.inCombat)
                }
            } catch (t: Throwable) {
                LogBus.e("engine", t.message ?: t.toString())
            }
            delay(60)
        }
    }
}
