package com.homeweb.lyriccar.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.model.LyricColorPreset
import com.homeweb.lyriccar.model.LyricFontScale
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.OverlayBackdrop
import com.homeweb.lyriccar.model.OverlayStyle

/**
 * 悬浮歌词设置：开关、权限引导、行数 / 字号 / 颜色 / 底衬、锁定和位置。
 *
 * 面板里放一个用 [OverlayLyricsView] 画的实时预览；真正的悬浮窗在 LyricCar
 * 自己的界面前台时是隐藏的，所以这里改完要回到桌面 / 导航才看得到。
 */
class OverlaySheet(
    private val activity: Activity,
    private val app: LyricCarApp,
) {
    private val sheet = SheetDialog(activity, "悬浮歌词")

    fun show() {
        rebuild()
        sheet.show()
    }

    private fun rebuild() {
        val body = sheet.body
        body.removeAllViews()
        val style = app.settings.overlay
        val canDraw = Settings.canDrawOverlays(activity)

        // 开关
        val switchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Palette.SURFACE_LIGHT, 12)
            setPaddingDp(18, 14, 18, 14)
        }
        val switchColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        switchColumn.addView(textView(activity, "在导航 / 桌面上显示歌词", 18, Palette.TEXT_PRIMARY))
        switchColumn.addView(
            textView(
                activity,
                if (style.enabled) "已开启 · 回到桌面或高德就能看到" else "关闭时不影响主界面歌词",
                14,
                Palette.TEXT_SECONDARY,
            ),
        )
        switchRow.addView(switchColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        switchRow.addView(
            Switch(activity).apply {
                isChecked = style.enabled
                thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
                trackTintList = ColorStateList.valueOf(withAlpha(Palette.ACCENT, 90))
                setOnCheckedChangeListener { _, value -> toggle(value) }
            },
        )
        body.addView(switchRow, matchWidthWrap())

        if (!canDraw) {
            body.addView(space(activity, 10))
            body.addView(
                textView(
                    activity,
                    "还没有「显示在其他应用上层」权限，开了也浮不出来。",
                    14,
                    Palette.WARNING,
                ),
            )
            body.addView(space(activity, 8))
            body.addView(
                outlinedButton(activity, "去开启悬浮窗权限", hPadding = 18) {
                    val host = activity as? MainActivity
                    if (host == null) {
                        toast(activity, "当前界面打不开授权页")
                    } else {
                        host.requestOverlayPermission()
                    }
                },
                matchWidthWrap(),
            )
            body.addView(space(activity, 8))
            body.addView(
                textView(
                    activity,
                    "车机设置里没有这一页的话，用电脑执行：\n" +
                        "adb shell appops set ${activity.packageName} SYSTEM_ALERT_WINDOW allow",
                    13,
                    Palette.TEXT_SECONDARY,
                ).apply { setTextIsSelectable(true) },
            )
        }

        // 比亚迪车机的「智能加速」回到桌面时会清后台，悬浮歌词会一起被杀掉 —— 引导去白名单。
        val rapidMode = rapidModeComponent()
        if (rapidMode != null) {
            body.addView(space(activity, 12))
            body.addView(
                textView(
                    activity,
                    "比亚迪车机提示：回到桌面时「智能加速」会清掉后台应用，悬浮歌词会跟着消失。\n" +
                        "把 LyricCar 加进加速白名单（允许后台运行）就能一直浮着。",
                    13,
                    Palette.WARNING,
                ).apply { setLineSpacing(0f, 1.4f) },
            )
            body.addView(space(activity, 8))
            body.addView(
                outlinedButton(activity, "打开加速白名单", hPadding = 18) {
                    try {
                        activity.startActivity(
                            Intent().setComponent(rapidMode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (error: Exception) {
                        toast(activity, "打不开加速白名单：这台车机没有这个页面")
                    }
                },
                matchWidthWrap(),
            )
        }

        // 预览
        body.addView(space(activity, 16))
        body.addView(textView(activity, "预览", 14, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 8))
        val previewFrame = android.widget.FrameLayout(activity).apply {
            background = roundedBackground(0xFF2B3A4B.toInt(), 16)
            setPaddingDp(10, 10, 10, 10)
        }
        val preview = OverlayLyricsView(activity).apply {
            maxWidthPx = (activity.resources.displayMetrics.widthPixels * 0.62f).toInt()
            applyStyle(style)
            setLyrics(PREVIEW_LINES, 1)
        }
        previewFrame.addView(
            preview,
            android.widget.FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        body.addView(previewFrame, matchWidthWrap())

        // 行数
        body.addView(space(activity, 16))
        body.addView(textView(activity, "显示行数", 14, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 8))
        body.addView(
            chipRow(
                OverlayStyle.LINE_CHOICES.map { count ->
                    chip("$count 行", style.lines == count) {
                        apply(style.copy(lines = count))
                    }
                },
            ),
            matchWidthWrap(),
        )

        // 字号
        body.addView(space(activity, 12))
        body.addView(textView(activity, "字号", 14, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 8))
        body.addView(
            chipRow(
                LyricFontScale.entries.map { font ->
                    chip(font.label, style.font == font) {
                        apply(style.copy(font = font))
                    }
                },
            ),
            matchWidthWrap(),
        )

        // 颜色
        body.addView(space(activity, 12))
        body.addView(textView(activity, "当前句颜色", 14, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 8))
        body.addView(colorRow(style), matchWidthWrap())

        // 底衬
        body.addView(space(activity, 12))
        body.addView(textView(activity, "底衬", 14, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 8))
        body.addView(
            chipRow(
                OverlayBackdrop.entries.map { backdrop ->
                    chip(backdrop.label, style.backdrop == backdrop) {
                        apply(style.copy(backdrop = backdrop))
                    }
                },
            ),
            matchWidthWrap(),
        )
        body.addView(space(activity, 6))
        body.addView(
            textView(
                activity,
                "底衬越深越不挑背景；选「无」时文字自带阴影。",
                13,
                Palette.TEXT_SECONDARY,
            ),
        )

        // 锁定 + 位置
        body.addView(space(activity, 16))
        val lockRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Palette.SURFACE_LIGHT, 12)
            setPaddingDp(18, 14, 18, 14)
        }
        val lockColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        lockColumn.addView(textView(activity, "锁定位置", 18, Palette.TEXT_PRIMARY))
        lockColumn.addView(
            textView(
                activity,
                "锁定后点不到悬浮窗，方便操作下面的导航；拖不动时先关掉它",
                14,
                Palette.TEXT_SECONDARY,
            ),
        )
        lockRow.addView(lockColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        lockRow.addView(
            Switch(activity).apply {
                isChecked = style.locked
                thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
                trackTintList = ColorStateList.valueOf(withAlpha(Palette.ACCENT, 90))
                setOnCheckedChangeListener { _, value -> apply(style.copy(locked = value)) }
            },
        )
        body.addView(lockRow, matchWidthWrap())

        body.addView(space(activity, 10))
        body.addView(
            outlinedButton(activity, "回到屏幕上方", hPadding = 18) {
                apply(style.copy(centerX = 0.5f, centerY = 0.12f))
            },
            matchWidthWrap(),
        )

        body.addView(space(activity, 16))
        body.addView(
            textView(
                activity,
                "拖动悬浮窗可以换位置，松手自动记住；点一下回到 LyricCar。\n" +
                    "LyricCar 自己的界面在前台时悬浮窗会暂时隐藏，不挡设置面板。",
                13,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.4f) },
            matchWidthWrap(),
        )
    }

    private fun toggle(enable: Boolean) {
        val style = app.settings.overlay
        if (enable && !Settings.canDrawOverlays(activity)) {
            toast(activity, "先给「显示在其他应用上层」权限")
            (activity as? MainActivity)?.requestOverlayPermission()
            rebuild()
            return
        }
        apply(style.copy(enabled = enable))
        toast(
            activity,
            if (enable) "已开启，回到桌面或导航就能看到" else "悬浮歌词已关闭",
        )
    }

    private fun apply(style: OverlayStyle) {
        app.setOverlay(style)
        rebuild()
    }

    private fun colorRow(style: OverlayStyle): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (preset in LyricColorPreset.entries) {
            val selected = style.color == preset
            val swatch = View(activity).apply {
                background = roundedBackground(
                    preset.colorFor(lightTheme = false),
                    999,
                    if (selected) Palette.TEXT_PRIMARY else Palette.STROKE,
                    if (selected) 2 else 1,
                )
                layoutParams = LinearLayout.LayoutParams(activity.dp(38), activity.dp(38))
                contentDescription = preset.label
                isClickable = true
                setOnClickListener { apply(style.copy(color = preset)) }
            }
            row.addView(swatch)
            row.addView(space(activity, 0).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
            })
        }
        return row
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        textView(
            activity,
            label,
            15,
            if (selected) Palette.DARK_TEXT else Palette.TEXT_PRIMARY,
        ).apply {
            background = roundedBackground(
                if (selected) Palette.ACCENT else Palette.SURFACE_LIGHT,
                999,
            )
            setPaddingDp(16, 8, 16, 8)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun chipRow(chips: List<TextView>): LinearLayout {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEachIndexed { index, view ->
            row.addView(view)
            if (index != chips.lastIndex) {
                row.addView(space(activity, 0).apply {
                    layoutParams = LinearLayout.LayoutParams(activity.dp(8), 1)
                })
            }
        }
        return row
    }

    /** 比亚迪「智能加速」的白名单页面；没装 / 不是比亚迪就返回 null。 */
    private fun rapidModeComponent(): ComponentName? {
        val component = ComponentName("com.byd.rapidmode", "com.byd.rapidmode.RapidModeActivity")
        return try {
            val resolved = activity.packageManager.resolveActivity(
                Intent().setComponent(component),
                0,
            )
            if (resolved != null) component else null
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val PREVIEW_LINES = listOf(
            LyricLine(0, "上一句歌词"),
            LyricLine(1_000, "这一句正在唱，长一点也能自动换行"),
            LyricLine(2_000, "下一句歌词"),
        )
    }
}
