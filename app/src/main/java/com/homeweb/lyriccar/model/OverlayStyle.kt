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

/** 悬浮歌词文字描边：底衬为「无」时靠黑边也能看清。 */
enum class OverlayOutline(val label: String, val widthDp: Float) {
    NONE("无", 0f),
    THIN("细", 1.5f),
    THICK("粗", 3f);

    companion object {
        fun fromName(name: String?): OverlayOutline =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: THIN
    }
}

/**
 * 悬浮歌词（浮在导航 / 桌面上）的样式和位置。
 *
 * 宽度是**固定**的（占屏幕宽度的百分比）：歌词长一句短一句时窗口不会左右跳，
 * 放不下的部分换行。位置存的是「窗口左上角占屏幕宽 / 高的比例」，
 * 换分辨率、转屏后按比例还原。
 */
data class OverlayStyle(
    val enabled: Boolean = false,
    /** 显示几句（以当前句为中心取窗口）。 */
    val lines: Int = DEFAULT_LINES,
    val font: LyricFontScale = LyricFontScale.NORMAL,
    val color: LyricColorPreset = LyricColorPreset.THEME,
    val backdrop: OverlayBackdrop = OverlayBackdrop.LIGHT,
    val outline: OverlayOutline = OverlayOutline.THIN,
    /** 窗口固定宽度，占屏幕宽度的百分比。 */
    val widthPercent: Int = DEFAULT_WIDTH_PERCENT,
    /** 锁定后点击穿透到下面的导航，只能回设置里解锁。 */
    val locked: Boolean = false,
    val leftFraction: Float = DEFAULT_LEFT,
    val topFraction: Float = DEFAULT_TOP,
) {
    fun sanitized(): OverlayStyle = copy(
        lines = if (lines in LINE_CHOICES) lines else {
            LINE_CHOICES.minByOrNull { abs(it - lines) } ?: DEFAULT_LINES
        },
        widthPercent = widthPercent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT),
        leftFraction = leftFraction.coerceIn(0f, 1f),
        topFraction = topFraction.coerceIn(0f, 1f),
    )

    /** 水平居中时的左上角比例（「回到屏幕上方」用）。 */
    fun centeredLeftFraction(): Float = (100 - widthPercent) / 200f

    companion object {
        const val DEFAULT_LINES = 3
        val LINE_CHOICES = listOf(1, 2, 3, 5)

        const val MIN_WIDTH_PERCENT = 30
        const val MAX_WIDTH_PERCENT = 95
        const val DEFAULT_WIDTH_PERCENT = 60
        const val DEFAULT_LEFT = 0.2f
        const val DEFAULT_TOP = 0.12f
    }
}
