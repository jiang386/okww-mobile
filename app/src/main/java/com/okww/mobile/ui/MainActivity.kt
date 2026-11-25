package com.okww.mobile.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.config.ConfigRepo
import com.okww.mobile.databinding.ActivityMainBinding
import com.okww.mobile.engine.RunState
import com.okww.mobile.service.AutoAccessibilityService
import com.okww.mobile.service.CaptureService
import com.okww.mobile.service.OverlayService
import com.okww.mobile.util.LogBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                CaptureService.requestStart(this, result.resultCode, data)
                Toast.makeText(this, "投屏已授权，开始采集", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "投屏授权已取消", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.tvLog.movementMethod = ScrollingMovementMethod()
        loadConfigToUi()
        bindActions()
        collectState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun loadConfigToUi() {
        val cfg = App.instance.cfg
        b.cbCloud.isChecked = cfg.enabledCloudGuard
        b.cbCombat.isChecked = cfg.enabledCombat
        b.cbPickup.isChecked = cfg.enabledPickup
        b.cbDialog.isChecked = cfg.enabledDialog
        b.cbSwitch.isChecked = cfg.enabledSwitch
    }

    private fun saveTaskFlags() {
        val cfg = App.instance.cfg
        cfg.enabledCloudGuard = b.cbCloud.isChecked
        cfg.enabledCombat = b.cbCombat.isChecked
        cfg.enabledPickup = b.cbPickup.isChecked
        cfg.enabledDialog = b.cbDialog.isChecked
        cfg.enabledSwitch = b.cbSwitch.isChecked
        ConfigRepo.save(this, cfg)
    }

    private fun bindActions() {
        b.btnCapture.setOnClickListener { requestCapture() }
        b.btnCapture.setOnLongClickListener {
            CaptureService.requestStop(this)
            LogBus.i("ui", "已停止投屏采集")
            true
        }
        b.btnAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗已授权", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        b.btnNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                Toast.makeText(this, "通知已授权", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnBattery.setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "已在电池优化白名单", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        b.btnLaunch.setOnClickListener {
            val url = App.instance.cfg.cloudUrl
            if (!ViaHelper.openCloud(this, url)) {
                Toast.makeText(this, R.string.toast_no_via, Toast.LENGTH_LONG).show()
            }
        }
        b.btnNative.setOnClickListener {
            if (!ViaHelper.openGame(this)) {
                Toast.makeText(this, R.string.toast_no_game, Toast.LENGTH_LONG).show()
            }
        }
        b.btnGameMode.setOnClickListener { showGameModeDialog() }
        b.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        b.btnPanel.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                OverlayService.show(this)
                Toast.makeText(this, "悬浮控制台已开启", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        b.btnStart.setOnClickListener {
            if (App.instance.engine.start()) {
                Toast.makeText(this, "已启动，切到 Via 后会开始执行", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, RunState.status.value, Toast.LENGTH_LONG).show()
            }
        }
        b.btnStop.setOnClickListener { App.instance.engine.stop() }
        b.btnHelp.setOnClickListener { showHelpDialog() }

        b.cbCloud.setOnCheckedChangeListener { _, _ -> saveTaskFlags() }
        b.cbCombat.setOnCheckedChangeListener { _, _ -> saveTaskFlags() }
        b.cbPickup.setOnCheckedChangeListener { _, _ -> saveTaskFlags() }
        b.cbDialog.setOnCheckedChangeListener { _, _ -> saveTaskFlags() }
        b.cbSwitch.setOnCheckedChangeListener { _, _ -> saveTaskFlags() }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (t: Throwable) {
            Toast.makeText(this, "无法发起投屏：${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RunState.status.collect { status -> b.tvStatus.text = status }
                }
                launch {
                    RunState.running.collect { running ->
                        b.btnStart.isEnabled = !running
                        b.btnStop.isEnabled = running
                    }
                }
                launch {
                    LogBus.lines.collect { lines ->
                        b.tvLog.text = lines.takeLast(16).joinToString("\n") { "${it.time} ${it.text}" }
                    }
                }
                launch {
                    while (true) {
                        refreshPermissionUi()
                        delay(2000)
                    }
                }
            }
        }
    }

    private fun refreshPermissionUi() {
        b.btnCapture.text =
            if (CaptureService.instance?.frame != null) "投屏 \u2713" else getString(R.string.btn_capture)
        b.btnAccess.text =
            if (AutoAccessibilityService.ready()) "无障碍 \u2713" else getString(R.string.btn_access)
        b.btnOverlay.text =
            if (Settings.canDrawOverlays(this)) "悬浮窗 \u2713" else getString(R.string.btn_overlay)
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        b.btnNotify.text = if (notifyOk) "通知 \u2713" else getString(R.string.btn_notify)
        val pm = getSystemService(PowerManager::class.java)
        b.btnBattery.text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "电池 \u2713" else getString(R.string.btn_battery)
        b.tvLog.visibility = View.VISIBLE
    }

    private fun showGameModeDialog() {
        val report = GameMode.apply(this)
        val msg = buildString {
            append(report)
            append("\nVia 操作：手机横放 → 打开云·鸣潮 → 点网页内全屏按钮。")
            append("\n若沉浸全屏失败，复制并执行一次 adb 命令即可获得完整横屏全屏能力。")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_game_mode)
            .setMessage(msg)
            .setPositiveButton(R.string.help_ok, null)
            .setNeutralButton(R.string.copy_adb) { _, _ -> copyAdb() }
            .setNegativeButton("还原系统设置") { _, _ -> GameMode.restore(this) }
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.help_ok, null)
            .setNeutralButton(R.string.copy_adb) { _, _ -> copyAdb() }
            .setNegativeButton("修改系统设置") { _, _ -> GameMode.openWriteSettings(this) }
            .show()
    }

    private fun copyAdb() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", ViaHelper.adbGrantCommand()))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
