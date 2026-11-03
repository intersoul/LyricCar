package com.homeweb.lyriccar.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.homeweb.lyriccar.model.LyricLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 歌词列表：固定行高、当前句高亮并自动居中，长句等比缩小到宽度以内，
 * 点击某句可以跳转到那句（回放时）。
 *
 * 字号 / 行距 / 水平对齐 / 当前句颜色都来自 [LyricStyle]，由设置面板控制；
 * 样式变化时调用 [applyStyle]，会按新行高重新把当前句滚到中间。
 */
class LyricsView(context: Context) : View(context) {

    private var lineHeight = dpF(BASE_LINE_DP)
    private val paddingTop = dpF(32)
    private val paddingHorizontal = dpF(28)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    private var activeSize = sp(BASE_ACTIVE_SP)
    private var inactiveSize = sp(BASE_INACTIVE_SP)
    private var style = LyricStyle()

    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var lastIndex = -1
    private var lastCount = -1
    private var scrollOffset = 0f
    private var animator: ValueAnimator? = null

    /** 真正的歌词界面用默认值；设置面板里的预览会关掉手势，交给外层 ScrollView。 */
    var interactive: Boolean = true

    var onSeek: ((LyricLine) -> Unit)? = null

    private var downY = 0f
    private var downScroll = 0f
    private var moved = false
    private val touchSlop = dpF(6)

    init {
        recalcSizes()
    }

    /** 应用字号 / 行距 / 对齐 / 颜色；当前句会按新行高重新居中。 */
    fun applyStyle(value: LyricStyle) {
        style = value
        recalcSizes()
        if (currentIndex >= 0 && height > 0) {
            scrollToLine(currentIndex, animate = false)
        }
        invalidate()
    }

    fun setLines(newLines: List<LyricLine>, current: Int) {
        val changed = newLines !== lines || current != lastIndex || newLines.size != lastCount
        lines = newLines
        currentIndex = current
        if (!changed) return
        lastCount = newLines.size
        lastIndex = current
        if (current >= 0) {
            if (height == 0) {
                post { scrollToLine(current, animate = true) }
            } else {
                scrollToLine(current, animate = true)
            }
        } else {
            invalidate()
        }
    }

    private fun recalcSizes() {
        activeSize = sp(BASE_ACTIVE_SP * style.fontScale)
        inactiveSize = sp(BASE_INACTIVE_SP * style.fontScale)
        lineHeight = dpF(BASE_LINE_DP * style.fontScale * style.lineSpacing)
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun maxScroll(): Float {
        val content = paddingTop * 2 + lines.size * lineHeight
        return max(0f, content - height)
    }

    private fun targetFor(index: Int): Float {
        val target = paddingTop + index * lineHeight - height / 2f + lineHeight / 2f
        return target.coerceIn(0f, maxScroll())
    }

    private fun scrollToLine(index: Int, animate: Boolean) {
        if (index < 0) return
        val target = targetFor(index)
        animator?.cancel()
        if (!animate || height == 0) {
            scrollOffset = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(scrollOffset, target).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                scrollOffset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return
        val available = width - paddingHorizontal * 2
        if (available <= 0) return

        val first = max(0, ((scrollOffset - paddingTop) / lineHeight).toInt())
        val last = min(lines.size - 1, ((scrollOffset + height - paddingTop) / lineHeight).toInt() + 1)

        for (i in first..last) {
            val line = lines[i]
            val active = i == currentIndex
            paint.textSize = if (active) activeSize else inactiveSize
            paint.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (active) style.activeColor else style.inactiveColor
            paint.textAlign = if (style.centered) Paint.Align.CENTER else Paint.Align.LEFT

            val top = paddingTop + i * lineHeight - scrollOffset
            val baseline = top + lineHeight / 2f - (paint.ascent() + paint.descent()) / 2f
            val measured = paint.measureText(line.text)
            val anchorX = if (style.centered) width / 2f else paddingHorizontal
            if (measured <= available || measured <= 0f) {
                canvas.drawText(line.text, anchorX, baseline, paint)
            } else {
                val scale = available / measured
                val save = canvas.save()
                canvas.translate(anchorX, baseline)
                canvas.scale(scale, 1f)
                canvas.drawText(line.text, 0f, 0f, paint)
                canvas.restoreToCount(save)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                downScroll = scrollOffset
                moved = false
                animator?.cancel()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = event.y - downY
                if (abs(delta) > touchSlop) moved = true
                scrollOffset = (downScroll - delta).coerceIn(0f, maxScroll())
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val index = ((event.y + scrollOffset - paddingTop) / lineHeight).toInt()
                    val seek = onSeek
                    if (seek != null && index in lines.indices) {
                        performClick()
                        seek(lines[index])
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BASE_ACTIVE_SP = 30f
        const val BASE_INACTIVE_SP = 24f
        const val BASE_LINE_DP = 58f
    }
}
