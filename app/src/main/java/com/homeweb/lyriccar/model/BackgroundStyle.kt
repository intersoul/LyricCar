package com.homeweb.lyriccar.model

/** 主界面背景的来源。 */
enum class BackgroundMode {
    DEFAULT,
    IMAGE,
    COLOR;

    companion object {
        fun fromName(name: String?): BackgroundMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 纯色背景预设。
 *
 * 深浅两套：深色主题用暗色、浅色主题用浅色，保证配对应主题时歌词和文字看得清。
 */
enum class BackgroundColorPreset(
    val label: String,
    val darkColor: Int,
    val lightColor: Int,
) {
    NIGHT("墨黑", 0xFF0E141A.toInt(), 0xFFF3F6F9.toInt()),
    INDIGO("藏青", 0xFF111C2B.toInt(), 0xFFE8EFF7.toInt()),
    FOREST("墨绿", 0xFF0F2019.toInt(), 0xFFE7F1EB.toInt()),
    PLUM("深紫", 0xFF1B1426.toInt(), 0xFFEFE9F6.toInt()),
    COFFEE("咖色", 0xFF221912.toInt(), 0xFFF5EEE7.toInt()),
    SLATE("石板", 0xFF191E23.toInt(), 0xFFEDF0F3.toInt());

    fun colorFor(lightTheme: Boolean): Int = if (lightTheme) lightColor else darkColor

    companion object {
        fun fromName(name: String?): BackgroundColorPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NIGHT
    }
}

/** 主界面背景设置：默认 / 自定义图片 / 纯色。 */
data class BackgroundStyle(
    val mode: BackgroundMode = BackgroundMode.DEFAULT,
    val color: BackgroundColorPreset = BackgroundColorPreset.NIGHT,
    /** 自定义图片存在应用私有目录里的文件名；为空表示还没选过。 */
    val imageName: String = "",
)
