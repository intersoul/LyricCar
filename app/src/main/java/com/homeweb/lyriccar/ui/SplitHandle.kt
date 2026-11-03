package com.homeweb.lyriccar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 分栏拖动条。
 *
 * 横向分栏时它是一条竖线（拖动改左右宽度），竖向分栏时是一条横线（拖动改上下高度）。
 * 这里只负责把「拖到了可用宽 / 高的百分之几」算出来交给 [onFraction]；
 * 最小宽度、权重、持久化都由 MainScreen 处理。
 *
 * 双击（300ms 内两次点击、且中间没有拖动）回调 [onReset]，用来恢复默认比例。
 */
class SplitHandle(context: Context, private val vertical: Boolean) : View(context) {

    /** 拖动过程中的实时比例。 */
    var onFraction: ((Float) -> Unit)? = null

    /** 松手 / 手势取消时回调最终比例，调用方在这里写盘。 */
    var onRelease: ((Float) -> Unit)? = null

    /** 双击复位。 */
    var onReset: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineThickness = dpF(2)
    private val gripLength = dpF(64)
    private val touchSlop = dpF(8)

    private var dragging = false
    private var moved = false
    private var doubleTap = false
    private var lastTapUpAt = 0L
    private var downAt = 0f
    private var fraction = 0.5f

    init {
        isClickable = true
        isFocusable = true
        contentDescription =
            if (vertical) "拖动调整上下分栏，双击复位" else "拖动调整左右分栏，双击复位"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (dragging) Palette.ACCENT else Palette.DIVIDER_STRONG
        val centerX = width / 2f
        val centerY = height / 2f
        val half = lineThickness / 2f
        if (vertical) {
            canvas.drawRoundRect(
                centerX - gripLength / 2f,
                centerY - half,
                centerX + gripLength / 2f,
                centerY + half,
                half,
                half,
                paint,
            )
        } else {
            canvas.drawRoundRect(
                centerX - half,
                centerY - gripLength / 2f,
                centerX + half,
                centerY + gripLength / 2f,
                half,
                half,
                paint,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                moved = false
                doubleTap = SystemClock.uptimeMillis() - lastTapUpAt < DOUBLE_TAP_MS
                downAt = if (vertical) event.y else event.x
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointer = if (vertical) event.y else event.x
                if (!moved && abs(pointer - downAt) > touchSlop) moved = true
                if (moved) {
                    fraction = fractionFor(pointer)
                    onFraction?.invoke(fraction)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                dragging = false
                invalidate()
                if (moved) {
                    onRelease?.invoke(fraction)
                } else {
                    performClick()
                    lastTapUpAt = SystemClock.uptimeMillis()
                    if (doubleTap) onReset?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                if (moved) onRelease?.invoke(fraction)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 把触点在父容器里的位置换算成「左 / 上栏占可用空间的比例」。 */
    private fun fractionFor(pointer: Float): Float {
        val host = parent as? View ?: return fraction
        val handleSize = if (vertical) height else width
        val usable = if (vertical) {
            host.height - host.paddingTop - host.paddingBottom - handleSize
        } else {
            host.width - host.paddingLeft - host.paddingRight - handleSize
        }
        if (usable <= 0) return fraction
        val start = if (vertical) top - host.paddingTop else left - host.paddingLeft
        val center = start + pointer - handleSize / 2f
        return (center / usable.toFloat()).coerceIn(0f, 1f)
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
    }
}
