package com.okww.mobile.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.config.AutoConfig
import com.okww.mobile.config.ConfigRepo
import com.okww.mobile.config.KeyPoint
import com.okww.mobile.databinding.ActivityCalibrationBinding
import com.okww.mobile.service.AutoAccessibilityService
import com.okww.mobile.service.CaptureService
import com.okww.mobile.service.Touch

/**
 * 按键添加 / 校准页。
 *
 * 不提供默认键位：首次进入时按键条里全部为未添加状态，
 * 玩家选中按键后在画面上点按即可放置；点击空白处会自动切换到下一个未添加按键。
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var b: ActivityCalibrationBinding
    private lateinit var cfg: AutoConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(b.root)

        cfg = AutoConfig.fromJson(App.instance.cfg.toJson())
        b.calView.cfg = cfg
        b.calView.onPointsChanged = { buildChips() }
        b.calView.selectNextUnset()
        loadFrame()

        b.btnRefresh.setOnClickListener { loadFrame() }
        b.btnReset.setOnClickListener {
            cfg.clearPoints()
            b.calView.selected = KeyPoint.entries.first()
            b.calView.invalidate()
            buildChips()
            Toast.makeText(this, R.string.cal_cleared, Toast.LENGTH_SHORT).show()
        }
        b.btnTest.setOnClickListener { testTap() }
        b.btnSave.setOnClickListener {
            if (cfg.enabledCombat && !cfg.isSet(KeyPoint.ATTACK)) {
                Toast.makeText(this, R.string.cal_need_attack, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ConfigRepo.save(this, cfg)
            App.instance.reloadConfig()
            Toast.makeText(this, R.string.cal_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        buildChips()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildChips() {
        b.chipRow.removeAllViews()
        KeyPoint.entries.forEach { kp ->
            val added = cfg.isSet(kp)
            val sel = b.calView.selected == kp
            val tv = TextView(this).apply {
                text = buildString {
                    if (sel) append("▶ ")
                    append(getString(kp.labelRes))
                    append(if (added) " ✓" else " ○")
                }
                setTextColor(ContextCompat.getColor(this@CalibrationActivity, if (added) R.color.text else R.color.muted))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
                textSize = 13f
                setOnClickListener {
                    b.calView.selected = kp
                    buildChips()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6f) }
            b.chipRow.addView(tv, lp)
        }
        val current = b.calView.selected
        b.tvCalDesc.text = buildString {
            append(getString(R.string.cal_desc))
            append("\n已添加 ${cfg.points.size}/${KeyPoint.entries.size}")
            if (current != null) {
                append(" · 当前：")
                append(getString(current.labelRes))
                if (!cfg.isSet(current)) append("（未添加）")
            }
        }
    }

    private fun loadFrame() {
        val frame = CaptureService.instance?.frame
        b.calView.setBackgroundBitmap(frame?.bitmap)
        if (frame == null) {
            b.tvCalDesc.text = getString(R.string.cal_noframe)
        } else {
            buildChips()
            b.tvCalDesc.append("\n当前画面: ${frame.bitmap.width}x${frame.bitmap.height}")
        }
    }

    private fun testTap() {
        if (!AutoAccessibilityService.ready()) {
            Toast.makeText(this, R.string.toast_no_access, Toast.LENGTH_SHORT).show()
            return
        }
        val key = b.calView.selected ?: KeyPoint.ATTACK
        val p = cfg.p(key)
        if (p == null) {
            Toast.makeText(this, R.string.cal_key_unset, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = Touch.tapNorm(p.x, p.y)
        val name = getString(key.labelRes)
        Toast.makeText(
            this,
            if (ok) "已点击「$name」" else "点击失败",
            Toast.LENGTH_SHORT
        ).show()
    }
}
