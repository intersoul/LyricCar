package com.homeweb.lyriccar.model

import kotlin.math.abs

/** 悬浮歌词的底衬：没有 / 淡 / 深，保证在导航地图上也看得清。 */
enum class OverlayBackdrop(val label: String, val alpha: Int) {
    NONE("无", 0),
    LIGHT("淡", 60),
    STRONG("深", 170);

    companion object {
        fun fromName(name: String?): OverlayBackdrop =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LIGHT
    }
}

/**
 * 悬浮歌词（浮在导航 / 桌面上）的样式和位置。
 *
 * 位置存的是「窗口中心点占屏幕宽 / 高的比例」，换分辨率、转屏后按比例还原；
 * 颜色 / 字号复用主界面的档位，不另造一套。
 */
data class OverlayStyle(
    val enabled: Boolean = false,
    /** 显示几句（以当前句为中心取窗口）。 */
    val lines: Int = DEFAULT_LINES,
    val font: LyricFontScale = LyricFontScale.NORMAL,
    val color: LyricColorPreset = LyricColorPreset.THEME,
    val backdrop: OverlayBackdrop = OverlayBackdrop.LIGHT,
    /** 锁定后点击穿透到下面的导航，只能回设置里解锁。 */
    val locked: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.12f,
) {
    fun sanitized(): OverlayStyle = copy(
        lines = if (lines in LINE_CHOICES) lines else {
            LINE_CHOICES.minByOrNull { abs(it - lines) } ?: DEFAULT_LINES
        },
        centerX = centerX.coerceIn(0.05f, 0.95f),
        centerY = centerY.coerceIn(0.02f, 0.95f),
    )

    companion object {
        const val DEFAULT_LINES = 3
        val LINE_CHOICES = listOf(1, 2, 3, 5)
    }
}
