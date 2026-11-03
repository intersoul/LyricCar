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
import com.homeweb.lyriccar.model.LyricDisplay
import com.homeweb.lyriccar.model.LyricFieldPreference
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.MediaInfo
import com.homeweb.lyriccar.model.ThemeMode
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

    fun notifyState() {
        mainHandler.post { stateListener?.invoke(lastState) }
    }
}
