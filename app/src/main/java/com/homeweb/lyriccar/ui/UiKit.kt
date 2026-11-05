package com.homeweb.lyriccar.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.homeweb.lyriccar.model.ThemeMode
import kotlin.math.roundToInt

/**
 * 两套配色。深色是默认，浅色给白天 / 反光严重的车机用。
 * 切主题走 Activity 重建，所以这里只需要在重建前把 [Palette.mode] 设好。
 */
private data class PaletteColors(
    val background: Int,
    val surface: Int,
    val surfaceLight: Int,
    val panelTop: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val stroke: Int,
    val darkText: Int,
    val error: Int,
    val warning: Int,
    val divider: Int,
    val dividerStrong: Int,
    val track: Int,
    val lyricInactive: Int,
    val logText: Int,
)

private val DARK_COLORS = PaletteColors(
    background = 0xFF0A0E13.toInt(),
    surface = 0xFF141B23.toInt(),
    surfaceLight = 0xFF182230.toInt(),
    panelTop = 0xFF16202B.toInt(),
    accent = 0xFF57C7FF.toInt(),
    textPrimary = 0xFFECEFF1.toInt(),
    textSecondary = 0xFF8FA3B0.toInt(),
    stroke = 0xFF2B3A4B.toInt(),
    darkText = 0xFF06121B.toInt(),
    error = 0xFFFF6B6B.toInt(),
    warning = 0xFFFFB74D.toInt(),
    divider = 0x22FFFFFF,
    dividerStrong = 0x22FFFFFF,
    track = 0x33FFFFFF,
    lyricInactive = 0xA68FA3B0.toInt(),
    logText = 0xFFB9C7D2.toInt(),
)

private val LIGHT_COLORS = PaletteColors(
    background = 0xFFF2F5F8.toInt(),
    surface = 0xFFFFFFFF.toInt(),
    surfaceLight = 0xFFE8EEF4.toInt(),
    panelTop = 0xFFFFFFFF.toInt(),
    accent = 0xFF0B6FA8.toInt(),
    textPrimary = 0xFF10171D.toInt(),
    textSecondary = 0xFF5A6B78.toInt(),
    stroke = 0xFFC9D4DE.toInt(),
    darkText = 0xFFFFFFFF.toInt(),
    error = 0xFFC62828.toInt(),
    warning = 0xFFB26A00.toInt(),
    divider = 0x1F000000,
    dividerStrong = 0x1F000000,
    track = 0x33000000,
    lyricInactive = 0xA65A6B78.toInt(),
    logText = 0xFF3E4C57.toInt(),
)

object Palette {
    /** 当前主题；在 Activity 建界面之前设置。 */
    var mode: ThemeMode = ThemeMode.DARK

    private val colors: PaletteColors
        get() = if (mode == ThemeMode.LIGHT) LIGHT_COLORS else DARK_COLORS

    val BACKGROUND: Int get() = colors.background
    val SURFACE: Int get() = colors.surface
    val SURFACE_LIGHT: Int get() = colors.surfaceLight
    val PANEL_TOP: Int get() = colors.panelTop
    val ACCENT: Int get() = colors.accent
    val TEXT_PRIMARY: Int get() = colors.textPrimary
    val TEXT_SECONDARY: Int get() = colors.textSecondary
    val STROKE: Int get() = colors.stroke
    val DARK_TEXT: Int get() = colors.darkText
    val ERROR: Int get() = colors.error
    val WARNING: Int get() = colors.warning
    val DIVIDER: Int get() = colors.divider
    val DIVIDER_STRONG: Int get() = colors.dividerStrong
    val TRACK: Int get() = colors.track
    val LYRIC_INACTIVE: Int get() = colors.lyricInactive
    val LOG_TEXT: Int get() = colors.logText
}

/** 浅色模式下的系统确认框也要浅色，否则白底下面弹一个黑框。 */
fun alertDialogTheme(): Int =
    if (Palette.mode == ThemeMode.LIGHT) {
        android.R.style.Theme_Material_Light_Dialog_Alert
    } else {
        android.R.style.Theme_Material_Dialog_Alert
    }

/**
 * 把设置里的主题解析成真正要用的深 / 浅色。
 *
 * 「跟随系统」优先读车机的夜间模式设置（`ui_night_mode`：1=白天 / 2=夜间）。
 * 为什么不用 `uiMode` 就够了：BYD 的框架把非白名单 App 的 uimode 配置变更
 * 直接吞掉（日志里的 `isBydUiModeChangeWhitelistApp`），界面不会重建；
 * 但设置项本身会变，所以我们监听它、自己决定要不要重建。
 * 设置项是「自动」（0）或读不到时，再看一次 `uiMode`（进程重启后仍有效）。
 */
fun ThemeMode.resolve(context: Context): ThemeMode = when (this) {
    ThemeMode.DARK -> ThemeMode.DARK
    ThemeMode.LIGHT -> ThemeMode.LIGHT
    ThemeMode.AUTO -> when (explicitNightSetting(context)) {
        true -> ThemeMode.DARK
        false -> ThemeMode.LIGHT
        null -> if (systemInNightMode(context)) ThemeMode.DARK else ThemeMode.LIGHT
    }
}

/** 车机当前是不是夜间模式（系统没上报时按白天处理）。 */
fun systemInNightMode(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * 车机「白天 / 夜间」设置：2=夜间、1=白天；0（自动）或读不到时返回 null。
 * 见 [resolve] 里对 BYD uimode 白名单的说明。
 */
fun explicitNightSetting(context: Context): Boolean? {
    val mode = try {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            "ui_night_mode",
        )
    } catch (_: Exception) {
        return null
    }
    return when (mode) {
        2 -> true
        1 -> false
        else -> null
    }
}

fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun Context.dp(value: Number): Int =
    (value.toFloat() * resources.displayMetrics.density).roundToInt()

fun dpF(value: Number): Float = value.toFloat() * android.content.res.Resources.getSystem().displayMetrics.density

fun View.setPaddingDp(left: Number, top: Number, right: Number, bottom: Number) {
    setPadding(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
}

fun View.margins(left: Number = 0, top: Number = 0, right: Number = 0, bottom: Number = 0) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
    layoutParams = params
}

fun roundedBackground(
    color: Int,
    radiusDp: Number,
    strokeColor: Int? = null,
    strokeWidthDp: Number = 1,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dpF(radiusDp)
    setColor(color)
    if (strokeColor != null) setStroke(dpF(strokeWidthDp).toInt(), strokeColor)
}

fun leftPanelBackground(alpha: Int = 255): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(withAlpha(Palette.PANEL_TOP, alpha), withAlpha(Palette.BACKGROUND, alpha)),
)

fun textView(
    context: Context,
    text: CharSequence = "",
    sizeSp: Number = 15,
    color: Int = Palette.TEXT_PRIMARY,
    bold: Boolean = false,
): TextView = TextView(context).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    includeFontPadding = false
}

fun filledButton(context: Context, label: String, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Palette.DARK_TEXT)
        background = roundedBackground(Palette.ACCENT, 12)
        setPaddingDp(24, 12, 24, 12)
        minimumHeight = 0
        minimumWidth = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun outlinedButton(
    context: Context,
    label: String,
    hPadding: Number = 20,
    onClick: () -> Unit,
): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette.TEXT_PRIMARY)
        background = roundedBackground(Color.TRANSPARENT, 12, Palette.STROKE, 1)
        setPaddingDp(hPadding, 10, hPadding, 10)
        minimumHeight = 0
        minimumWidth = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun textButton(context: Context, label: String, onClick: () -> Unit): Button =
    Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Palette.ACCENT)
        background = null
        setPaddingDp(12, 8, 12, 8)
        minimumHeight = 0
        minimumWidth = 0
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

fun verticalLayout(context: Context): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

fun horizontalLayout(context: Context): LinearLayout =
    LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

fun space(context: Context, heightDp: Number): View =
    View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.dp(heightDp)) }

fun fullSpace(context: Context, heightDp: Number): View =
    View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(heightDp),
        )
    }

fun dividerLine(context: Context): View =
    View(context).apply {
        setBackgroundColor(Palette.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(1),
        )
    }

fun toast(context: Context, message: String, long: Boolean = false) {
    Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun weightParams(weight: Float): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)

fun matchWidthWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** 圆角裁切的 ImageView（封面）。 */
class RoundedImageView(context: Context) : ImageView(context) {
    private val clip = Path()
    private val rect = RectF()
    var radiusPx: Float = dpF(16)

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }
}

fun centeredText(context: Context, text: CharSequence, sizeSp: Number, color: Int): TextView =
    textView(context, text, sizeSp, color).apply {
        gravity = Gravity.CENTER
    }
