package com.okww.mobile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.okww.mobile.App
import com.okww.mobile.R
import com.okww.mobile.config.AutoConfig
import com.okww.mobile.config.ConfigRepo
import com.okww.mobile.config.KeyPoint
import com.okww.mobile.databinding.ActivityCalibrationBinding
import com.okww.mobile.service.AutoAccessibilityService
import com.okww.mobile.service.CaptureService
import com.okww.mobile.service.Touch

class CalibrationActivity : AppCompatActivity() {

    private lateinit var b: ActivityCalibrationBinding
    private lateinit var cfg: AutoConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(b.root)

        cfg = AutoConfig.fromJson(App.instance.cfg.toJson())
        b.calView.cfg = cfg
        loadFrame()

        b.btnRefresh.setOnClickListener { loadFrame() }
        b.btnReset.setOnClickListener {
            cfg.resetPoints()
            b.calView.invalidate()
        }
        b.btnTest.setOnClickListener { testTap() }
        b.btnSave.setOnClickListener {
            ConfigRepo.save(this, cfg)
            App.instance.reloadConfig()
            Toast.makeText(this, R.string.cal_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadFrame() {
        val frame = CaptureService.instance?.frame
        b.calView.setBackgroundBitmap(frame?.bitmap)
        b.tvCalDesc.text = if (frame == null) {
            getString(R.string.cal_noframe)
        } else {
            getString(R.string.cal_desc) + "\n当前画面: ${frame.bitmap.width}x${frame.bitmap.height}"
        }
    }

    private fun testTap() {
        if (!AutoAccessibilityService.ready()) {
            Toast.makeText(this, R.string.toast_no_access, Toast.LENGTH_SHORT).show()
            return
        }
        val key = b.calView.selected ?: KeyPoint.ATTACK
        val p = cfg.p(key)
        val ok = Touch.tapNorm(p.x, p.y)
        val name = getString(key.labelRes)
        Toast.makeText(
            this,
            if (ok) "已点击「$name」" else "点击失败",
            Toast.LENGTH_SHORT
        ).show()
    }
}
