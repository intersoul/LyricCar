package com.homeweb.lyriccar.model

/** 歌词字号档位。 */
enum class LyricFontScale(val label: String, val scale: Float) {
    SMALL("小", 0.85f),
    NORMAL("标准", 1.0f),
    LARGE("大", 1.2f),
    XL("特大", 1.45f);

    companion object {
        fun fromName(name: String?): LyricFontScale =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}

/** 歌词行距档位。 */
enum class LyricLineSpacing(val label: String, val scale: Float) {
    TIGHT("紧凑", 0.85f),
    NORMAL("标准", 1.0f),
    LOOSE("宽松", 1.2f);

    companion object {
        fun fromName(name: String?): LyricLineSpacing =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}

/** 歌词水平对齐。 */
enum class LyricAlignment(val label: String) {
    LEFT("左对齐"),
    CENTER("居中");

    companion object {
        fun fromName(name: String?): LyricAlignment =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: LEFT
    }
}

/**
 * 当前句颜色。
 *
 * 每个档位都配了深浅两套色值：车机白天反光时会切浅色模式，
 * 亮色在浅底上会糊，所以浅色模式改用同色系的深色版本。
 */
enum class LyricColorPreset(
    val label: String,
    val darkColor: Int,
    val lightColor: Int,
) {
    THEME("主题色", 0xFF57C7FF.toInt(), 0xFF0B6FA8.toInt()),
    GOLD("暖黄", 0xFFFFC857.toInt(), 0xFF8A5A00.toInt()),
    PINK("粉", 0xFFFF8FB8.toInt(), 0xFFA3245B.toInt()),
    GREEN("绿", 0xFF86E39B.toInt(), 0xFF1B6E33.toInt()),
    ORANGE("橙", 0xFFFFA45C.toInt(), 0xFF9A4A00.toInt()),
    CONTRAST("高对比", 0xFFF2F5F8.toInt(), 0xFF1B2530.toInt());

    fun colorFor(lightTheme: Boolean): Int = if (lightTheme) lightColor else darkColor

    companion object {
        fun fromName(name: String?): LyricColorPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: THEME
    }
}

/** 歌词显示样式（全部可以在「设置 → 歌词样式」里调）。 */
data class LyricDisplay(
    val font: LyricFontScale = LyricFontScale.NORMAL,
    val spacing: LyricLineSpacing = LyricLineSpacing.NORMAL,
    val alignment: LyricAlignment = LyricAlignment.LEFT,
    val color: LyricColorPreset = LyricColorPreset.THEME,
)
