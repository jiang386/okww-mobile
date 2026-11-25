package com.okww.mobile

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.okww.mobile.config.AutoConfig
import com.okww.mobile.config.ConfigRepo
import com.okww.mobile.engine.AutomationEngine
import com.okww.mobile.util.LogBus

class App : Application() {

    lateinit var cfg: AutoConfig
        private set

    /** 控制台 / 校准界面在前台时，自动化暂停，避免点到自己的界面。 */
    @Volatile
    var uiVisible: Boolean = false
        private set

    val engine: AutomationEngine by lazy { AutomationEngine(this) }

    private var resumedCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = ConfigRepo.load(this)
        createChannels()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                resumedCount++
                uiVisible = resumedCount > 0
            }

            override fun onActivityPaused(activity: Activity) {
                resumedCount = (resumedCount - 1).coerceAtLeast(0)
                uiVisible = resumedCount > 0
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        LogBus.i("app", "OKWW 移动版已启动")
    }

    fun reloadConfig() {
        cfg = ConfigRepo.load(this)
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, getString(R.string.notif_channel_capture), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_OVERLAY, getString(R.string.notif_channel_overlay), NotificationManager.IMPORTANCE_MIN)
        )
    }

    companion object {
        lateinit var instance: App
            private set
        const val CH_CAPTURE = "capture"
        const val CH_OVERLAY = "overlay"
    }
}
