package com.homeweb.lyriccar.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.data.AppSettings
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.model.BackgroundMode
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.ThemeMode
import com.homeweb.lyriccar.overlay.LyricOverlayService
import com.homeweb.lyriccar.transfer.DownloadsRepository

/** 唯一界面：横屏全屏歌词。 */
class MainActivity : Activity() {

    private lateinit var app: LyricCarApp
    private lateinit var screen: MainScreen
    private lateinit var downloads: DownloadsRepository

    /** 用户被引导去开悬浮窗权限；回来时若已经授权，就把他刚才要的悬浮窗打开。 */
    private var pendingOverlayEnable = false

    private val stateHandler: (LyricSessionState) -> Unit = { state -> screen.render(state) }
    private val themeHandler: () -> Unit = { applyThemeIfNeeded() }
    private val settingsHandler: (AppSettings) -> Unit = {
        applyKeepScreenOn()
        if (::screen.isInitialized) {
            screen.applyLyricDisplay()
            screen.applyBackground()
            screen.onChannelChanged()
        }
        applyThemeIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as LyricCarApp
        // 界面里到处读 Palette，所以必须在建视图之前把主题定好。
        Palette.mode = app.settings.theme.resolve(this)
        downloads = DownloadsRepository(this)
        screen = MainScreen(this, app, downloads)
        setContentView(screen)

        app.addStateObserver(stateHandler)
        app.settingsListener = settingsHandler
        app.themeWatcher = themeHandler

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
        val resolved = app.settings.theme.resolve(this)
        if (Palette.mode == resolved) return
        Palette.mode = resolved
        window.decorView.post {
            if (!isFinishing && !isDestroyed) recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        app.activityForeground = true
        applyKeepScreenOn()
        applyThemeIfNeeded()
        screen.refreshNotificationAccess()
        app.repository.checkAccessAndRearm()
        app.runSelfCheckAsync()
        screen.render(app.currentState())
        finishPendingOverlay()
        LyricOverlayService.refresh(this)
    }

    override fun onPause() {
        app.activityForeground = false
        app.store.flush()
        app.diagnostics.flush()
        super.onPause()
    }

    override fun onDestroy() {
        app.removeStateObserver(stateHandler)
        if (app.settingsListener === settingsHandler) app.settingsListener = null
        if (app.themeWatcher === themeHandler) app.themeWatcher = null
        super.onDestroy()
    }

    /** 打开系统文件管理器 / 图库选一张背景图。 */
    fun pickBackgroundImage() {
        // 优先用系统自带的 DocumentsUI：它会把选中文件的读权限一起授过来。
        // 部分车机文件管理器（实测 BYD）返回的是没授权的 content:// URI，读不了。
        val candidates = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                setPackage("com.android.documentsui")
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            },
        )
        for (intent in candidates) {
            try {
                Diagnostics.log("[背景] 打开选择器：${intent.action}")
                startActivityForResult(intent, REQUEST_BACKGROUND_IMAGE)
                return
            } catch (error: Exception) {
                Diagnostics.log("[背景] 打开选择器失败：$error")
            }
        }
        toast(this, "这台车机没有可用的图片选择器")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 有的 ROM 直接返回 RESULT_CANCELED，但权限其实已经给了；统一在 onResume 里再确认。
            finishPendingOverlay()
            LyricOverlayService.refresh(this)
            return
        }
        if (requestCode != REQUEST_BACKGROUND_IMAGE) return
        val uri: Uri = data?.data ?: run {
            Diagnostics.log("[背景] 选择器返回但没有图片：resultCode=$resultCode")
            return
        }
        Diagnostics.log("[背景] 选择器返回：resultCode=$resultCode uri=$uri")
        toast(this, "正在读取图片…")
        Thread {
            val bitmap = try {
                BackgroundImages.load(this, uri)
            } catch (error: Exception) {
                Diagnostics.log("[背景] 读取异常：$error")
                null
            }
            Diagnostics.log(
                "[背景] 解码结果：" + (
                    bitmap?.let { "${it.width}x${it.height}" } ?: "失败"
                    ),
            )
            runOnUiThread {
                if (bitmap == null) {
                    toast(this, "这张图片读不出来，换一张试试")
                } else {
                    showBackgroundCropper(bitmap)
                }
            }
        }.start()
    }

    private fun showBackgroundCropper(bitmap: Bitmap) {
        BackgroundCropperDialog(this, bitmap) { cropped ->
            val saved = BackgroundImages.save(this, cropped, BackgroundImages.targetWidth(this))
            if (saved) {
                app.setBackground(
                    // 保留用户选的左栏底色 / 纯色档位，只把模式换成图片。
                    app.settings.background.copy(
                        mode = BackgroundMode.IMAGE,
                        imageName = LyricCarApp.BACKGROUND_IMAGE_NAME,
                    ),
                )
                toast(this, "背景已更新")
            } else {
                toast(this, "保存背景失败")
            }
            if (!cropped.isRecycled) cropped.recycle()
        }.show()
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

    // ----------------------------------------------------------
    // 悬浮歌词权限
    // ----------------------------------------------------------

    /**
     * 请求「显示在其他应用上层」。有的车机 ROM 把这个页面删了，
     * 弹不出系统页面时给出 adb 兜底命令。
     */
    fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast(this, "已经拥有悬浮窗权限")
            return
        }
        pendingOverlayEnable = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            Diagnostics.log("[Overlay] 打开悬浮窗授权页")
        } catch (error: Exception) {
            Diagnostics.log("[Overlay] 悬浮窗授权页打不开：$error")
            toast(this, "这台车机没有悬浮窗授权页，请用 adb 授权（见设置里的提示）", long = true)
        }
    }

    /** 从授权页回来：用户刚才想开悬浮歌词的话，现在补上。 */
    private fun finishPendingOverlay() {
        if (!pendingOverlayEnable) return
        if (!Settings.canDrawOverlays(this)) return
        pendingOverlayEnable = false
        if (!app.settings.overlay.enabled) {
            app.setOverlay(app.settings.overlay.copy(enabled = true))
        }
        LyricOverlayService.refresh(this)
        toast(this, "悬浮歌词已开启，回到桌面或导航就能看到")
        Diagnostics.log("[Overlay] 授权完成，悬浮歌词已开启")
    }

    private companion object {
        const val REQUEST_BACKGROUND_IMAGE = 1001
        const val REQUEST_OVERLAY_PERMISSION = 1002
    }
}
