package com.homeweb.lyriccar.ui

import com.homeweb.lyriccar.model.LyricAlignment
import com.homeweb.lyriccar.model.LyricColorPreset
import com.homeweb.lyriccar.model.LyricDisplay
import com.homeweb.lyriccar.model.ThemeMode

/**
 * LyricsView 真正要用的样式：颜色已经按当前深浅主题解析好了，
 * 字号 / 行距折算成倍数，具体的像素值由 LyricsView 自己算。
 */
data class LyricStyle(
    val fontScale: Float = 1f,
    val lineSpacing: Float = 1f,
    val centered: Boolean = false,
    val activeColor: Int = Palette.ACCENT,
    val inactiveColor: Int = Palette.LYRIC_INACTIVE,
)

fun lyricStyleOf(display: LyricDisplay): LyricStyle {
    val light = Palette.mode == ThemeMode.LIGHT
    val active = if (display.color == LyricColorPreset.THEME) {
        Palette.ACCENT
    } else {
        display.color.colorFor(light)
    }
    return LyricStyle(
        fontScale = display.font.scale,
        lineSpacing = display.spacing.scale,
        centered = display.alignment == LyricAlignment.CENTER,
        activeColor = active,
        inactiveColor = Palette.LYRIC_INACTIVE,
    )
}
