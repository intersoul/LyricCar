package com.homeweb.lyriccar.overlay

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.R
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.LyricMode
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.ui.MainActivity
import com.homeweb.lyriccar.ui.OverlayLyricsView

/**
 * 悬浮歌词：像网易云桌面歌词那样，把当前几句歌词浮在导航 / 桌面之上。
 *
 * - 前台服务维持，App 退到后台、打开高德导航时歌词照常滚动；
 * - 窗口 `TYPE_APPLICATION_OVERLAY`，不需要通知使用权，但要「显示在其他应用上层」；
 * - 自己在 LyricCar 界面里时隐藏，回到导航 / 桌面自动出现；
 * - 行数 / 字号 / 颜色 / 底衬 / 位置都来自 `settings.overlay`，改完即时生效。
 */
class LyricOverlayService : Service() {

    private lateinit var app: LyricCarApp
    private lateinit var windowManager: WindowManager

    private var view: OverlayLyricsView? = null
    private var params: WindowManager.LayoutParams? = null
    private var activityVisible = false

    private var windowLines: List<LyricLine> = emptyList()
    private var windowIndex = -2

    private val stateObserver: (LyricSessionState) -> Unit = { state -> render(state) }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            activityVisible = true
            updateVisibility()
        }

        override fun onActivityPaused(activity: Activity) {
            activityVisible = false
            updateVisibility()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        app = application as LyricCarApp
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            Diagnostics.log("[Overlay] 没有悬浮窗权限，悬浮服务退出")
            stopSelf()
            return
        }

        app.registerActivityLifecycleCallbacks(lifecycle)
        app.addStateObserver(stateObserver)
        // 服务可能在界面已经打开时才启动，先按主界面当前的前后台状态定一次。
        activityVisible = app.activityForeground
        showWindow()
        Diagnostics.log("[Overlay] 悬浮歌词已启动")
        render(app.currentState())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Diagnostics.log("[Overlay] 通知栏关闭悬浮歌词")
                app.setOverlay(app.settings.overlay.copy(enabled = false))
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 样式变了 / 服务被重启：按最新设置重建一次。
        if (view == null) {
            if (Settings.canDrawOverlays(this)) showWindow() else stopSelf()
        } else {
            restyle()
        }
        render(app.currentState())
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        restyle()
        updateVisibility()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Diagnostics.log("[Overlay] 悬浮歌词已停止")
        try {
            app.removeStateObserver(stateObserver)
            app.unregisterActivityLifecycleCallbacks(lifecycle)
        } catch (_: Exception) {
            // Application 已经不在了
        }
        removeWindow()
        super.onDestroy()
    }

    // ----------------------------------------------------------
    // 窗口
    // ----------------------------------------------------------
    private fun showWindow() {
        if (view != null) return
        val style = app.settings.overlay
        val metrics = resources.displayMetrics
        val overlayView = OverlayLyricsView(this).apply {
            maxWidthPx = (metrics.widthPixels * 0.8f).toInt()
            applyStyle(style)
            onTap = { openApp() }
            onDrag = { dx, dy -> moveBy(dx, dy) }
            onDragEnd = { savePosition() }
        }
        val windowParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LyricCar 悬浮歌词"
            if (style.locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.addView(overlayView, windowParams)
        } catch (error: Exception) {
            Diagnostics.log("[Overlay] 添加悬浮窗失败：$error")
            stopSelf()
            return
        }
        view = overlayView
        params = windowParams
        overlayView.post { applyStoredPosition() }
        Diagnostics.log(
            "[Overlay] 悬浮窗：${style.lines} 行 · 字号=${style.font.label} · " +
                "颜色=${style.color.label} · 底衬=${style.backdrop.label}" +
                if (style.locked) " · 锁定" else "",
        )
    }

    private fun removeWindow() {
        val current = view ?: return
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
            // 已经没了
        }
        view = null
        params = null
    }

    private fun restyle() {
        val current = view ?: return
        val style = app.settings.overlay
        val metrics = resources.displayMetrics
        current.maxWidthPx = (metrics.widthPixels * 0.8f).toInt()
        current.applyStyle(style)
        val p = params ?: return
        p.flags = if (style.locked) {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(current, p)
        } catch (_: Exception) {
            // 窗口已经没了
        }
        current.post { applyStoredPosition() }
    }

    /** 按设置里的中心点比例把窗口摆回去。 */
    private fun applyStoredPosition() {
        val current = view ?: return
        val p = params ?: return
        if (current.width <= 0 || current.height <= 0) return
        val metrics = resources.displayMetrics
        val style = app.settings.overlay
        p.x = ((metrics.widthPixels - current.width) * style.centerX).toInt()
        p.y = ((metrics.heightPixels - current.height) * style.centerY).toInt()
        try {
            windowManager.updateViewLayout(current, p)
        } catch (_: Exception) {
            // 窗口已经没了
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val current = view ?: return
        val p = params ?: return
        val metrics = resources.displayMetrics
        p.x = (p.x + dx.toInt()).coerceIn(0, (metrics.widthPixels - current.width).coerceAtLeast(0))
        p.y = (p.y + dy.toInt()).coerceIn(0, (metrics.heightPixels - current.height).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(current, p)
        } catch (_: Exception) {
            // 窗口已经没了
        }
    }

    private fun savePosition() {
        val current = view ?: return
        val p = params ?: return
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels - current.width).coerceAtLeast(1)
        val height = (metrics.heightPixels - current.height).coerceAtLeast(1)
        app.saveOverlayPosition(
            centerX = (p.x.toFloat() / width),
            centerY = (p.y.toFloat() / height),
        )
    }

    // ----------------------------------------------------------
    // 歌词内容
    // ----------------------------------------------------------
    private fun render(state: LyricSessionState) {
        val current = view ?: return
        val all = state.lines
        if (state.mode == LyricMode.IDLE || all.isEmpty()) {
            if (windowLines.isEmpty()) return
            windowLines = emptyList()
            windowIndex = -1
            current.setLyrics(emptyList(), -1)
            updateVisibility()
            return
        }
        val count = app.settings.overlay.lines.coerceIn(1, 9)
        val index = state.currentIndex
        val maxStart = (all.size - count).coerceAtLeast(0)
        val start = if (index < 0) 0 else (index - (count - 1) / 2).coerceIn(0, maxStart)
        val end = (start + count).coerceAtMost(all.size)
        val window = ArrayList<LyricLine>(end - start)
        for (i in start until end) window.add(all[i])
        val local = if (index in start until end) index - start else -1
        if (window == windowLines && local == windowIndex) return
        windowLines = window
        windowIndex = local
        current.setLyrics(window, local)
        updateVisibility()
    }

    /** LyricCar 自己在前台、或者没有歌词时把窗口藏起来（占 0 尺寸，不挡点击）。 */
    private fun updateVisibility() {
        val current = view ?: return
        val show = !activityVisible && windowLines.isNotEmpty()
        val target = if (show) View.VISIBLE else View.GONE
        if (current.visibility != target) {
            current.visibility = target
            if (show) current.post { applyStoredPosition() }
        }
    }

    // ----------------------------------------------------------
    // 其它
    // ----------------------------------------------------------
    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        } catch (error: Exception) {
            Diagnostics.log("[Overlay] 打开主界面失败：$error")
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮歌词",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "悬浮歌词正在屏幕上显示"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags,
        )
        val close = PendingIntent.getService(
            this,
            1,
            Intent(this, LyricOverlayService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_lyric_notification)
            .setContentTitle("悬浮歌词")
            .setContentText("歌词正浮在屏幕上，点这里回到 LyricCar")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "关闭悬浮歌词", close)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lyric_overlay"
        private const val NOTIFICATION_ID = 201
        private const val ACTION_START = "com.homeweb.lyriccar.overlay.START"
        private const val ACTION_STOP = "com.homeweb.lyriccar.overlay.STOP"

        /** 设置变了 / 回到前台：按「开关 + 权限」决定启动、刷新还是停掉。 */
        fun refresh(context: Context) {
            val app = context.applicationContext as? LyricCarApp ?: return
            val style = app.settings.overlay
            if (!style.enabled) {
                stop(context)
                return
            }
            if (!Settings.canDrawOverlays(context)) {
                stop(context)
                return
            }
            start(context)
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LyricOverlayService::class.java))
            } catch (_: Exception) {
                // 服务本来就没跑
            }
        }

        /** 用户刚授权回来：把之前想开的悬浮窗开起来。 */
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        private fun start(context: Context) {
            val intent = Intent(context, LyricOverlayService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (error: Exception) {
                Diagnostics.log("[Overlay] 启动悬浮服务失败：$error")
            }
        }
    }
}
