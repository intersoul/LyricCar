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
import com.homeweb.lyriccar.model.PanelTone
import com.homeweb.lyriccar.model.ThemeMode
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

    @Volatile
    var stateListener: ((LyricSessionState) -> Unit)? = null

    @Volatile
    var settingsListener: ((AppSettings) -> Unit)? = null

    private val directory by lazy { carlyricDirectory(this) }

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics.install(this)
        settings = AppSettings.load(directory)
        store = LyricStore.open(directory)
        pruneWeakEntries()
        engine = LyricCaptureEngine(
            sink = store,
            field = settings.field,
            log = { message -> diagnostics.log(message) },
        )
        repository = MediaSourceCoordinator(this)
        repository.listener = { info ->
            lastInfo = info
            val state = engine.handle(info)
            lastState = state
            stateListener?.invoke(state)
        }
        repository.start()
        runSelfCheckAsync()
    }

    fun currentState(): LyricSessionState = lastState

    fun refreshEngine() {
        lastInfo?.let { info ->
            lastState = engine.handle(info)
            stateListener?.invoke(lastState)
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
        diagnostics.log("[LyricCar] 设置：外观=${mode.label}")
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

    fun notifyState() {
        mainHandler.post { stateListener?.invoke(lastState) }
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
