package com.homeweb.lyriccar.data

import com.homeweb.lyriccar.model.BackgroundColorPreset
import com.homeweb.lyriccar.model.BackgroundMode
import com.homeweb.lyriccar.model.BackgroundStyle
import com.homeweb.lyriccar.model.LyricAlignment
import com.homeweb.lyriccar.model.LyricColorPreset
import com.homeweb.lyriccar.model.LyricDisplay
import com.homeweb.lyriccar.model.LyricFieldPreference
import com.homeweb.lyriccar.model.LyricFontScale
import com.homeweb.lyriccar.model.LyricLineSpacing
import com.homeweb.lyriccar.model.OverlayBackdrop
import com.homeweb.lyriccar.model.OverlayOutline
import com.homeweb.lyriccar.model.OverlayStyle
import com.homeweb.lyriccar.model.PanelTone
import com.homeweb.lyriccar.model.SourceChannel
import com.homeweb.lyriccar.model.ThemeMode
import org.json.JSONObject
import java.io.File

/** 应用设置。存成 <files>/carlyric/settings.json，与旧版格式一致。 */
data class AppSettings(
    val field: LyricFieldPreference = LyricFieldPreference.AUTO,
    val keepScreenOn: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    /** 左栏占宽比例（0~1），拖动分栏时更新。 */
    val splitRatio: Float = DEFAULT_SPLIT_RATIO,
    /** 歌词显示样式：字号 / 行距 / 对齐 / 当前句颜色。 */
    val lyric: LyricDisplay = LyricDisplay(),
    /** 主界面背景：默认 / 图片 / 纯色。 */
    val background: BackgroundStyle = BackgroundStyle(),
    /** 播放信息的来源通道：自动 / 蓝牙 / 系统会话。 */
    val channel: SourceChannel = SourceChannel.AUTO,
    /** 悬浮歌词：开关 / 行数 / 字号 / 颜色 / 底衬 / 锁定 / 位置。 */
    val overlay: OverlayStyle = OverlayStyle(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("field", field.name.lowercase())
        put("channel", channel.name.lowercase())
        put("keepScreenOn", keepScreenOn)
        put("theme", theme.name.lowercase())
        put("splitRatio", splitRatio.toDouble())
        put("lyricFont", lyric.font.name.lowercase())
        put("lyricSpacing", lyric.spacing.name.lowercase())
        put("lyricAlign", lyric.alignment.name.lowercase())
        put("lyricColor", lyric.color.name.lowercase())
        put("bgMode", background.mode.name.lowercase())
        put("bgColor", background.color.name.lowercase())
        put("bgImage", background.imageName)
        put("bgPanel", background.panel.name.lowercase())
        put("overlayEnabled", overlay.enabled)
        put("overlayLines", overlay.lines)
        put("overlayFont", overlay.font.name.lowercase())
        put("overlayColor", overlay.color.name.lowercase())
        put("overlayBackdrop", overlay.backdrop.name.lowercase())
        put("overlayOutline", overlay.outline.name.lowercase())
        put("overlayWidth", overlay.widthPercent)
        put("overlayLocked", overlay.locked)
        put("overlayLeft", overlay.leftFraction.toDouble())
        put("overlayTop", overlay.topFraction.toDouble())
    }

    fun save(directory: File) {
        try {
            if (!directory.exists()) directory.mkdirs()
            File(directory, FILE_NAME).writeText(toJson().toString())
        } catch (error: Exception) {
            Diagnostics.current?.log("设置写入失败：$error")
        }
    }

    companion object {
        const val FILE_NAME = "settings.json"

        /** 默认左栏 40%，正好是以前的 4:6。 */
        const val DEFAULT_SPLIT_RATIO = 0.40f
        const val MIN_SPLIT_RATIO = 0.20f
        const val MAX_SPLIT_RATIO = 0.80f

        fun sanitizeSplitRatio(value: Float): Float =
            if (value.isNaN()) DEFAULT_SPLIT_RATIO else value.coerceIn(MIN_SPLIT_RATIO, MAX_SPLIT_RATIO)

        fun load(directory: File): AppSettings {
            return try {
                val file = File(directory, FILE_NAME)
                if (!file.exists()) return AppSettings()
                val json = JSONObject(file.readText())
                AppSettings(
                    field = LyricFieldPreference.fromName(json.optString("field")),
                    keepScreenOn = if (json.has("keepScreenOn")) {
                        json.optBoolean("keepScreenOn", true)
                    } else {
                        true
                    },
                    theme = ThemeMode.fromName(json.optString("theme")),
                    splitRatio = sanitizeSplitRatio(
                        json.optDouble("splitRatio", DEFAULT_SPLIT_RATIO.toDouble()).toFloat(),
                    ),
                    lyric = LyricDisplay(
                        font = LyricFontScale.fromName(json.optString("lyricFont")),
                        spacing = LyricLineSpacing.fromName(json.optString("lyricSpacing")),
                        alignment = LyricAlignment.fromName(json.optString("lyricAlign")),
                        color = LyricColorPreset.fromName(json.optString("lyricColor")),
                    ),
                    background = BackgroundStyle(
                        mode = BackgroundMode.fromName(json.optString("bgMode")),
                        color = BackgroundColorPreset.fromName(json.optString("bgColor")),
                        imageName = json.optString("bgImage"),
                        panel = PanelTone.fromName(json.optString("bgPanel")),
                    ),
                    channel = SourceChannel.fromName(json.optString("channel")),
                    overlay = OverlayStyle(
                        enabled = json.optBoolean("overlayEnabled", false),
                        lines = json.optInt("overlayLines", OverlayStyle.DEFAULT_LINES),
                        font = LyricFontScale.fromName(json.optString("overlayFont")),
                        color = LyricColorPreset.fromName(json.optString("overlayColor")),
                        backdrop = OverlayBackdrop.fromName(json.optString("overlayBackdrop")),
                        outline = OverlayOutline.fromName(json.optString("overlayOutline")),
                        widthPercent = json.optInt(
                            "overlayWidth",
                            OverlayStyle.DEFAULT_WIDTH_PERCENT,
                        ),
                        locked = json.optBoolean("overlayLocked", false),
                        leftFraction = json.optDouble(
                            "overlayLeft",
                            OverlayStyle.DEFAULT_LEFT.toDouble(),
                        ).toFloat(),
                        topFraction = json.optDouble(
                            "overlayTop",
                            OverlayStyle.DEFAULT_TOP.toDouble(),
                        ).toFloat(),
                    ).sanitized(),
                )
            } catch (error: Exception) {
                Diagnostics.current?.log("设置读取失败，用默认值：$error")
                AppSettings()
            }
        }
    }
}
