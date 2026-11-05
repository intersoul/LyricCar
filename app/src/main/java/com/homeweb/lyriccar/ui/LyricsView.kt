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

/**
 * 歌词列表：当前句高亮并自动居中，长句按面板宽度换行（不截断、不压扁），
 * 点击某句可以跳转到那句（回放时）。
 *
 * 字号 / 行距 / 水平对齐 / 当前句颜色都来自 [LyricStyle]，由设置面板控制；
 * 样式变化时调用 [applyStyle]，会按新的行高重新把当前句滚到中间。
 *
 * 布局按「歌词句 → 若干显示行」组织：每句先量出要占几行，再算累计高度，
 * 滚动、自动居中、点击都基于这份累计高度，所以换行后依然对得准。
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

    /** 每句歌词换行后的结果。宽度 / 字号不变时复用，不每帧重算。 */
    private var blocks: List<LyricBlock> = emptyList()
    private var blockTops = FloatArray(0)
    private var contentHeight = 0f
    private var layoutDirty = true
    private var layoutWidth = 0
    private var layoutActiveSize = 0f
    private var layoutInactiveSize = 0f

    /** 真正的歌词界面用默认值；设置面板里的预览会关掉手势，交给外层 ScrollView。 */
    var interactive: Boolean = true

    var onSeek: ((LyricLine) -> Unit)? = null

    private var downY = 0f
    private var downScroll = 0f
    private var moved = false
    private val touchSlop = dpF(6)

    private data class LyricBlock(val rows: List<String>, val height: Float)

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
        if (current != currentIndex) layoutDirty = true
        lines = newLines
        currentIndex = current
        if (!changed) return
        lastCount = newLines.size
        lastIndex = current
        layoutDirty = true
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
        layoutDirty = true
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    /**
     * 按当前宽度 / 字号把每句歌词换成显示行。换行结果缓存在 [blocks] 里，
     * 只有宽度、字号、歌词列表或当前句变化时才重算。
     */
    private fun ensureLayout() {
        if (!layoutDirty && layoutWidth == width &&
            layoutActiveSize == activeSize && layoutInactiveSize == inactiveSize
        ) {
            return
        }
        layoutDirty = false
        layoutWidth = width
        layoutActiveSize = activeSize
        layoutInactiveSize = inactiveSize
        blocks = emptyList()
        blockTops = FloatArray(0)
        contentHeight = 0f

        val available = width - paddingHorizontal * 2
        if (available <= 0f || lines.isEmpty()) return

        val result = ArrayList<LyricBlock>(lines.size)
        val tops = FloatArray(lines.size)
        var top = 0f
        for (i in lines.indices) {
            val block = layoutBlock(lines[i].text, i == currentIndex, available)
            tops[i] = top
            top += block.height
            result.add(block)
        }
        blocks = result
        blockTops = tops
        contentHeight = top
    }

    private fun layoutBlock(text: String, active: Boolean, available: Float): LyricBlock {
        paint.textSize = if (active) activeSize else inactiveSize
        paint.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val rows = wrapLyricRows(paint, text, available)
        return LyricBlock(rows, rows.size * lineHeight)
    }

    private fun maxScroll(): Float {
        val content = paddingTop * 2 + contentHeight
        return max(0f, content - height)
    }

    private fun targetFor(index: Int): Float {
        if (index !in blocks.indices) return 0f
        val target = paddingTop + blockTops[index] + blocks[index].height / 2f - height / 2f
        return target.coerceIn(0f, maxScroll())
    }

    private fun scrollToLine(index: Int, animate: Boolean) {
        if (index < 0 || index >= lines.size) return
        if (height == 0 || width == 0) {
            // 还没量过尺寸：等一帧再滚；已经不在窗口上就别再排队了（否则空转）。
            if (isAttachedToWindow) post { scrollToLine(index, animate) }
            return
        }
        ensureLayout()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutDirty = true
        if (currentIndex >= 0) scrollToLine(currentIndex, animate = false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return
        if (width <= 0) return
        ensureLayout()
        if (blocks.isEmpty()) return

        scrollOffset = scrollOffset.coerceIn(0f, maxScroll())

        for (i in blocks.indices) {
            val block = blocks[i]
            val blockTop = paddingTop + blockTops[i] - scrollOffset
            if (blockTop + block.height < -lineHeight) continue
            if (blockTop > height + lineHeight) break

            val active = i == currentIndex
            paint.textSize = if (active) activeSize else inactiveSize
            paint.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (active) style.activeColor else style.inactiveColor
            paint.textAlign = if (style.centered) Paint.Align.CENTER else Paint.Align.LEFT
            val anchorX = if (style.centered) width / 2f else paddingHorizontal

            block.rows.forEachIndexed { rowIndex, text ->
                val rowTop = blockTop + rowIndex * lineHeight
                val baseline = rowTop + lineHeight / 2f - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(text, anchorX, baseline, paint)
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
                    val index = blockIndexAt(event.y + scrollOffset - paddingTop)
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

    /** 某个纵向位置落在第几句上；落在两句之间的空隙里返回 -1。 */
    private fun blockIndexAt(y: Float): Int {
        if (blocks.isEmpty()) return -1
        var low = 0
        var high = blocks.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (blockTops[mid] <= y) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return -1
        if (y > blockTops[found] + blocks[found].height) return -1
        return found
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
