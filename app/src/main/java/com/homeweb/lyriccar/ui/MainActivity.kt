package com.homeweb.lyriccar.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.data.AppSettings
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.ThemeMode
import com.homeweb.lyriccar.transfer.DownloadsRepository

/** 唯一界面：横屏全屏歌词。 */
class MainActivity : Activity() {

    private lateinit var app: LyricCarApp
    private lateinit var screen: MainScreen
    private lateinit var downloads: DownloadsRepository

    private val stateHandler: (LyricSessionState) -> Unit = { state -> screen.render(state) }
    private val settingsHandler: (AppSettings) -> Unit = {
        applyKeepScreenOn()
        if (::screen.isInitialized) screen.applyLyricDisplay()
        applyThemeIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LyricCarApp
        // 界面里到处读 Palette，所以必须在建视图之前把主题定好。
        Palette.mode = app.settings.theme
        downloads = DownloadsRepository(this)
        screen = MainScreen(this, app, downloads)
        setContentView(screen)

        app.stateListener = stateHandler
        app.settingsListener = settingsHandler

        setupWindow()
        screen.render(app.currentState())
    }

    private fun setupWindow() {
        val light = Palette.mode == ThemeMode.LIGHT
        window.setBackgroundDrawable(ColorDrawable(Palette.BACKGROUND))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            // 浅色背景要深色图标，深色背景反过来；mask 保持两位都设。
            val appearance = if (light) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        }
    }

    /** 主题变了就整页重建：所有 Palette 颜色都是在建视图时取值的。 */
    private fun applyThemeIfNeeded() {
        if (Palette.mode == app.settings.theme) return
        Palette.mode = app.settings.theme
        window.decorView.post {
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
        screen.refreshNotificationAccess()
        app.repository.checkAccessAndRearm()
        app.runSelfCheckAsync()
        screen.render(app.currentState())
    }

    override fun onPause() {
        app.store.flush()
        app.diagnostics.flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (app.stateListener === stateHandler) app.stateListener = null
        if (app.settingsListener === settingsHandler) app.settingsListener = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        downloads.onPermissionResult(requestCode, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun applyKeepScreenOn() {
        if (app.settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
