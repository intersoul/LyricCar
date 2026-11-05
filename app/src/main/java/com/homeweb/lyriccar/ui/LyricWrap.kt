package com.homeweb.lyriccar.ui

import android.graphics.Paint

/**
 * 把一句歌词按实际渲染宽度拆成多行。
 *
 * 车机屏幕宽度有限，一句长歌词（英文长句、中文一整段）如果硬画会被截断或压扁。
 * 这里用 [Paint.breakText] 按当前字号 / 字重 / 可用宽度算出每行能放多少字，
 * 多出来的部分换到下一行 —— 不丢字、不缩字。
 *
 * - 先按 `\n` 分段，段落内部再折行；
 * - 单个字符比整行还宽时也会单独占一行（保证不丢字）；
 * - 不会把 emoji 这类代理对从中间拆开。
 */
fun wrapLyricRows(paint: Paint, text: String, maxWidth: Float): List<String> {
    if (text.isEmpty()) return listOf("")
    if (maxWidth <= 0f) return listOf(text)

    val rows = ArrayList<String>(2)
    for (paragraph in text.split('\n')) {
        if (paragraph.isEmpty()) {
            rows.add("")
            continue
        }
        var start = 0
        while (start < paragraph.length) {
            var count = paint.breakText(paragraph, start, paragraph.length, true, maxWidth, null)
            if (count <= 0) count = 1
            var end = start + count
            if (end < paragraph.length && Character.isHighSurrogate(paragraph[end - 1])) {
                end = (end - 1).coerceAtLeast(start + 1)
            }
            rows.add(paragraph.substring(start, end))
            start = end
        }
    }
    return rows.ifEmpty { listOf(text) }
}
