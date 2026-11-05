package com.homeweb.lyriccar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.OverlayStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮歌词的内容视图。
 *
 * 只负责画：当前句加粗高亮，前后各取几句；每句按窗口宽度换行（复用
 * [wrapLyricRows]），窗口大小随内容走。拖动 / 点击由 [onDrag] / [onTap] 交给服务处理，
 * 服务再更新 WindowManager 的位置。
 */
class OverlayLyricsView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val backdropRect = RectF()

    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var style = OverlayStyle()

    private var rows: List<Row> = emptyList()
    private var contentHeight = 0f
    private var contentWidth = 0f
    private var layoutDirty = true
    private var layoutLimit = -1

    /** 窗口最大宽度（屏幕宽度的 80%），由服务设置。 */
    var maxWidthPx: Int = 0

    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private data class Row(val text: String, val active: Boolean, val textSize: Float, val height: Float)

    fun applyStyle(value: OverlayStyle) {
        style = value
        layoutDirty = true
        invalidate()
        requestLayout()
    }

    fun setLyrics(newLines: List<LyricLine>, current: Int) {
        lines = newLines
        currentIndex = current
        layoutDirty = true
        invalidate()
        requestLayout()
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun activeColor(): Int = style.color.colorFor(lightTheme = false)

    private fun inactiveColor(): Int = withAlpha(activeColor(), 140)

    private fun ensureLayout(maxWidth: Int) {
        if (!layoutDirty && layoutLimit == maxWidth) return
        layoutDirty = false
        layoutLimit = maxWidth

        val side = dp(PADDING_SIDE_DP)
        val inner = (maxWidth - side * 2).coerceAtLeast(dp(40f))
        val scale = style.font.scale
        val activeSize = sp(BASE_ACTIVE_SP) * scale
        val inactiveSize = sp(BASE_INACTIVE_SP) * scale
        val gap = dp(ROW_GAP_DP)

        val result = ArrayList<Row>(lines.size * 2)
        var naturalWidth = 0f
        for (i in lines.indices) {
            val active = i == currentIndex
            val size = if (active) activeSize else inactiveSize
            paint.textSize = size
            paint.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            naturalWidth = max(naturalWidth, paint.measureText(lines[i].text))
            val wrapped = wrapLyricRows(paint, lines[i].text, inner)
            val rowHeight = size * ROW_HEIGHT_FACTOR
            for (text in wrapped) {
                result.add(Row(text, active, size, rowHeight))
            }
        }
        rows = result
        contentHeight = result.sumOf { it.height.toDouble() }.toFloat() +
            gap * (result.size - 1).coerceAtLeast(0) + side * 2
        contentWidth = (naturalWidth + side * 2).coerceAtMost(maxWidth.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (maxWidthPx > 0) maxWidthPx else MeasureSpec.getSize(widthMeasureSpec)
        ensureLayout(limit)
        val width = contentWidth.toInt().coerceAtLeast(dp(1f).toInt())
        setMeasuredDimension(width, contentHeight.toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) return
        val side = dp(PADDING_SIDE_DP)
        val radius = dp(16f)
        val backdrop = style.backdrop.alpha
        if (backdrop > 0) {
            backdropRect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = Color.argb(backdrop, 0, 0, 0)
            canvas.drawRoundRect(backdropRect, radius, radius, paint)
        }

        val gap = dp(ROW_GAP_DP)
        var top = side
        var index = 0
        for (row in rows) {
            paint.textSize = row.textSize
            paint.typeface = if (row.active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (row.active) activeColor() else inactiveColor()
            paint.textAlign = Paint.Align.CENTER
            if (backdrop == 0) {
                paint.setShadowLayer(dp(4f), 0f, dp(1f), 0xAA000000.toInt())
            } else {
                paint.clearShadowLayer()
            }
            val baseline = top + row.height / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(row.text, width / 2f, baseline, paint)
            top += row.height
            index++
            if (index < rows.size) top += gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                moved = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                if (moved) {
                    onDrag?.invoke(event.rawX - downX, event.rawY - downY)
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (moved) onDragEnd?.invoke() else performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (moved) onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    private companion object {
        const val PADDING_SIDE_DP = 22f
        const val ROW_GAP_DP = 6f
        const val BASE_ACTIVE_SP = 26f
        const val BASE_INACTIVE_SP = 20f
        const val ROW_HEIGHT_FACTOR = 1.35f
    }
}
