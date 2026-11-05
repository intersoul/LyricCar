package com.homeweb.lyriccar

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.homeweb.lyriccar.core.LyricCaptureEngine
import com.homeweb.lyriccar.data.AppSettings
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.data.LyricStore
import com.homeweb.lyriccar.data.carlyricDirectory
import com.homeweb.lyriccar.media.MediaSourceCoordinator
import com.homeweb.lyriccar.model.BackgroundMode
import com.homeweb.lyriccar.model.BackgroundStyle
import com.homeweb.lyriccar.model.LyricDisplay
import com.homeweb.lyriccar.model.LyricFieldPreference
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.MediaInfo
import com.homeweb.lyriccar.model.OverlayStyle
import com.homeweb.lyriccar.model.PanelTone
import com.homeweb.lyriccar.model.SourceChannel
import com.homeweb.lyriccar.model.ThemeMode
import com.homeweb.lyriccar.overlay.LyricOverlayService
import com.homeweb.lyriccar.ui.Palette
import com.homeweb.lyriccar.ui.explicitNightSetting
import com.homeweb.lyriccar.ui.resolve
import java.io.File
import kotlin.math.abs

/**
 * 应用级单例：歌词库、设置、采集引擎、媒体会话读取器都活在这里，
 * 所以 Activity 重建（浮窗返回 / 配置变化）不会丢掉正在采集的歌词。
 */
class LyricCarApp : Application() {

    lateinit var diagnostics: Diagnostics
        private set

    lateinit var store: LyricStore
        private set

    lateinit var settings: AppSettings
        private set

    lateinit var engine: LyricCaptureEngine
        private set

    lateinit var repository: MediaSourceCoordinator
        private set

    /** 本次启动清掉了几首「没采到东西」的记录（歌词库面板会提一句）。 */
    var lastPruneCount: Int = 0
        private set

    /** 读一次就归零：这条提示只在打开歌词库时提一句。 */
    fun consumePruneCount(): Int {
        val value = lastPruneCount
        lastPruneCount = 0
        return value
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastInfo: MediaInfo? = null
    private var lastState: LyricSessionState = LyricSessionState()

    /** 主界面是不是在前台；悬浮歌词据此决定「藏起来还是浮出来」。 */
    @Volatile
    var activityForeground: Boolean = false

    /** 「跟随系统」时车机昼夜模式变了：界面据此重新解析主题并重建。 */
    @Volatile
    var themeWatcher: (() -> Unit)? = null

    /** BYD 的框架会吞掉非白名单 App 的 uimode 变更，只能监听设置项自己判断。 */
    private val nightModeObserver = object : android.database.ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            if (settings.theme != ThemeMode.AUTO) return
            val night = explicitNightSetting(this@LyricCarApp)
            diagnostics.log(
                "[LyricCar] 车机昼夜模式变了：${night?.let { if (it) "夜间" else "白天" } ?: "自动"}",
            )
            themeWatcher?.invoke()
            LyricOverlayService.refresh(this@LyricCarApp)
        }
    }

    /** 主界面和悬浮歌词都要看「现在唱到哪句」，所以状态改成多播。 */
    private val stateObservers = java.util.concurrent.CopyOnWriteArrayList<(LyricSessionState) -> Unit>()

    fun addStateObserver(observer: (LyricSessionState) -> Unit) {
        if (!stateObservers.contains(observer)) stateObservers.add(observer)
    }

    fun removeStateObserver(observer: (LyricSessionState) -> Unit) {
        stateObservers.remove(observer)
    }

    @Volatile
    var settingsListener: ((AppSettings) -> Unit)? = null

    private val directory by lazy { carlyricDirectory(this) }

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics.install(this)
        settings = AppSettings.load(directory)
        // 界面 / 悬浮窗都按解析后的深浅色建视图，Application 阶段先定好。
        Palette.mode = settings.theme.resolve(this)
        contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor("ui_night_mode"),
            false,
            nightModeObserver,
        )
        store = LyricStore.open(directory)
        pruneWeakEntries()
        engine = LyricCaptureEngine(
            sink = store,
            field = settings.field,
            log = { message -> diagnostics.log(message) },
        )
        repository = MediaSourceCoordinator(this, settings.channel)
        repository.listener = { info ->
            lastInfo = info
            val state = engine.handle(info)
            lastState = state
            for (observer in stateObservers) observer(state)
        }
        repository.start()
        runSelfCheckAsync()
    }

    fun currentState(): LyricSessionState = lastState

    fun refreshEngine() {
        lastInfo?.let { info ->
            lastState = engine.handle(info)
            for (observer in stateObservers) observer(lastState)
        }
    }

    fun setField(field: LyricFieldPreference) {
        if (settings.field == field) return
        settings = settings.copy(field = field)
        settings.save(directory)
        engine.setField(field)
        diagnostics.log("[LyricCar] 设置：歌词写在 ${field.label}")
        refreshEngine()
        settingsListener?.invoke(settings)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        if (settings.keepScreenOn == enabled) return
        settings = settings.copy(keepScreenOn = enabled)
        settings.save(directory)
        settingsListener?.invoke(settings)
    }

    /** 深浅色切换；界面收到 settingsListener 后自己重建。 */
    fun setTheme(mode: ThemeMode) {
        if (settings.theme == mode) return
        settings = settings.copy(theme = mode)
        settings.save(directory)
        // 不要在这里改 Palette.mode：界面要靠「旧值 != 新解析值」判断该重建。
        diagnostics.log("[LyricCar] 设置：外观=${mode.label}")
        settingsListener?.invoke(settings)
        LyricOverlayService.refresh(this)
    }

    /** 换播放信息的来源通道（自动 / 蓝牙 / 系统会话），立刻重选一次数据。 */
    fun setChannel(channel: SourceChannel) {
        if (settings.channel == channel) return
        settings = settings.copy(channel = channel)
        settings.save(directory)
        repository.setChannel(channel)
        diagnostics.log("[LyricCar] 设置：通道=${channel.label}")
        settingsListener?.invoke(settings)
    }

    /** 拖动分栏松手时写盘，不触发界面重建。 */
    fun setSplitRatio(ratio: Float) {
        val next = AppSettings.sanitizeSplitRatio(ratio)
        if (abs(next - settings.splitRatio) < 0.01f) return
        val previous = settings.splitRatio
        settings = settings.copy(splitRatio = next)
        settings.save(directory)
        diagnostics.log(
            "[LyricCar] 设置：分栏 ${(previous * 100).toInt()}% → ${(next * 100).toInt()}%",
        )
    }

    /** 歌词样式变更：立刻应用到正在显示的歌词，不需要重建界面。 */
    fun setLyricDisplay(display: LyricDisplay) {
        if (settings.lyric == display) return
        settings = settings.copy(lyric = display)
        settings.save(directory)
        diagnostics.log(
            "[LyricCar] 设置：歌词样式 字号=${display.font.label} 行距=${display.spacing.label} " +
                "对齐=${display.alignment.label} 颜色=${display.color.label}",
        )
        settingsListener?.invoke(settings)
    }

    fun runSelfCheckAsync() {
        Thread({
            try {
                diagnostics.selfCheck(
                    hasNotificationAccess = { repository.hasNotificationAccess() },
                    listenerComponent = { repository.listenerComponentName() },
                    diagnose = { repository.diagnoseText() },
                    alternativeSource = { repository.hasAlternativeSource() },
                    channelState = {
                        "${settings.channel.label}（${repository.channelStateText()}）"
                    },
                )
            } catch (error: Exception) {
                diagnostics.log("环境自查失败：$error")
            }
        }, "lyriccar-self-check").apply {
            isDaemon = true
            start()
        }
    }

    /** 主界面背景变更：立刻应用，不需要重建界面。 */
    fun setBackground(style: BackgroundStyle) {
        if (settings.background == style) return
        settings = settings.copy(background = style)
        settings.save(directory)
        val label = when (style.mode) {
            BackgroundMode.DEFAULT -> "默认"
            BackgroundMode.IMAGE -> "自定义图片"
            BackgroundMode.COLOR -> "纯色 ${style.color.label}"
        }
        diagnostics.log("[LyricCar] 设置：背景=$label")
        settingsListener?.invoke(settings)
    }

    /** 自定义背景图片存在这里的私有目录，不申请存储权限。 */
    fun backgroundImageFile(): File = File(directory, BACKGROUND_IMAGE_NAME)

    /** 图片背景时左栏的底色：半透明 / 透明。 */
    fun setPanelTone(tone: PanelTone) {
        val background = settings.background
        if (background.panel == tone) return
        settings = settings.copy(background = background.copy(panel = tone))
        settings.save(directory)
        diagnostics.log("[LyricCar] 设置：左栏底色=${tone.label}")
        settingsListener?.invoke(settings)
    }

    /** 恢复默认背景，并删掉已经保存的图片。 */
    fun resetBackground() {
        val file = backgroundImageFile()
        if (file.exists()) file.delete()
        // 左栏底色是显示偏好，不是背景本身，留着。
        setBackground(BackgroundStyle(panel = settings.background.panel))
    }

    /** 悬浮歌词的设置变更：写盘并让悬浮服务按新样式重建 / 开始 / 停止。 */
    fun setOverlay(style: OverlayStyle) {
        val next = style.sanitized()
        if (settings.overlay == next) {
            // 样式没变也要让服务重新读一次（比如刚拿到悬浮窗权限）。
            LyricOverlayService.refresh(this)
            return
        }
        settings = settings.copy(overlay = next)
        settings.save(directory)
        diagnostics.log(
            "[LyricCar] 设置：悬浮歌词=${if (next.enabled) "开" else "关"} " +
                "${next.lines} 行 · 字号=${next.font.label} · 颜色=${next.color.label} · " +
                "底衬=${next.backdrop.label}${if (next.locked) " · 锁定位置" else ""}",
        )
        LyricOverlayService.refresh(this)
    }

    /** 拖动悬浮窗松手：只记位置，不触发服务重建。 */
    fun saveOverlayPosition(centerX: Float, centerY: Float) {
        val overlay = settings.overlay
        val next = overlay.copy(centerX = centerX, centerY = centerY).sanitized()
        if (abs(next.centerX - overlay.centerX) < 0.001f &&
            abs(next.centerY - overlay.centerY) < 0.001f
        ) {
            return
        }
        settings = settings.copy(overlay = next)
        settings.save(directory)
    }

    fun notifyState() {
        mainHandler.post {
            for (observer in stateObservers) observer(lastState)
        }
    }

    /** 启动时清一遍歌词库：听了几秒就跳走的歌不必留着。 */
    private fun pruneWeakEntries() {
        val removed = try {
            store.pruneWeakEntries()
        } catch (error: Exception) {
            diagnostics.log("[LyricCar] 歌词库清理失败：$error")
            return
        }
        lastPruneCount = removed.size
        if (removed.isEmpty()) return
        removed.forEach { song ->
            diagnostics.log(
                "[LyricCar] 歌词库清理：《${song.displayName}》" +
                    "${song.lines.size} 句 / 覆盖 ${song.spanMs / 1000} 秒 —— 太短，删掉",
            )
        }
        diagnostics.log("[LyricCar] 歌词库清理：共删掉 ${removed.size} 首")
    }

    companion object {
        const val BACKGROUND_IMAGE_NAME = "background.jpg"
    }
}
