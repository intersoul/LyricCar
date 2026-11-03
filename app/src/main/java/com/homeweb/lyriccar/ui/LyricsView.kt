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
 */
class LyricsView(context: Context) : View(context) {

    private val lineHeight = dpF(58)
    private val paddingTop = dpF(32)
    private val paddingHorizontal = dpF(28)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }
    private val activeSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics,
    )
    private val inactiveSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics,
    )

    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var lastIndex = -1
    private var lastCount = -1
    private var scrollOffset = 0f
    private var animator: ValueAnimator? = null

    var onSeek: ((LyricLine) -> Unit)? = null

    private var downY = 0f
    private var downScroll = 0f
    private var moved = false
    private val touchSlop = dpF(6)

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
        if (!animate) {
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
            paint.color = if (active) Palette.ACCENT else Palette.LYRIC_INACTIVE

            val top = paddingTop + i * lineHeight - scrollOffset
            val baseline = top + lineHeight / 2f - (paint.ascent() + paint.descent()) / 2f
            val measured = paint.measureText(line.text)
            if (measured <= available || measured <= 0f) {
                canvas.drawText(line.text, paddingHorizontal, baseline, paint)
            } else {
                val scale = available / measured
                val save = canvas.save()
                canvas.translate(paddingHorizontal, baseline)
                canvas.scale(scale, 1f)
                canvas.drawText(line.text, 0f, 0f, paint)
                canvas.restoreToCount(save)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
}
