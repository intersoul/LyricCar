package com.homeweb.lyriccar.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.homeweb.lyriccar.BuildConfig
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.R
import com.homeweb.lyriccar.lan.LyricLanServer
import com.homeweb.lyriccar.model.BackgroundColorPreset
import com.homeweb.lyriccar.model.BackgroundMode
import com.homeweb.lyriccar.model.BackgroundStyle
import com.homeweb.lyriccar.model.LyricAlignment
import com.homeweb.lyriccar.model.LyricColorPreset
import com.homeweb.lyriccar.model.LyricDisplay
import com.homeweb.lyriccar.model.LyricFieldPreference
import com.homeweb.lyriccar.model.LyricFontScale
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.LyricLineSpacing
import com.homeweb.lyriccar.model.PanelTone
import com.homeweb.lyriccar.model.StoredSong
import com.homeweb.lyriccar.model.ThemeMode
import com.homeweb.lyriccar.transfer.DownloadedFile
import com.homeweb.lyriccar.transfer.DownloadsRepository
import com.homeweb.lyriccar.transfer.diagnosticsFileName
import com.homeweb.lyriccar.transfer.lyricExportFileName
import java.util.Calendar
import java.util.Locale

/** 底部面板式的通用 Dialog：深色、圆角、可滚动。 */
class SheetDialog(
    private val activity: Activity,
    title: String,
    private val onDismissed: (() -> Unit)? = null,
) {
    val dialog = android.app.Dialog(activity, R.style.Theme_LyricCar_Sheet)
    val body = LinearLayout(activity)

    init {
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            background = roundedBackground(Palette.SURFACE, 20)
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(28, 20, 28, 24)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            textView(activity, title, 24, Palette.TEXT_PRIMARY, bold = true),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(textButton(activity, "✕") { dialog.dismiss() })
        column.addView(header, matchWidthWrap())
        column.addView(space(activity, 10))
        column.addView(dividerLine(activity))
        column.addView(space(activity, 14))

        body.orientation = LinearLayout.VERTICAL
        column.addView(body, matchWidthWrap())

        scroll.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            val width = (metrics.widthPixels - activity.dp(24)).coerceAtLeast(activity.dp(320))
            val height = (metrics.heightPixels * 0.88f).toInt()
            dialog.window?.apply {
                setLayout(width, height)
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.7f)
            }
            // 车机的导航栏会盖在 Dialog 底部，把内容底部留出来，最后一行才点得到。
            val insets = activity.window?.decorView?.rootWindowInsets
            val bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            } else {
                @Suppress("DEPRECATION")
                insets?.systemWindowInsetBottom ?: 0
            }
            column.setPadding(
                activity.dp(28),
                activity.dp(20),
                activity.dp(28),
                activity.dp(24) + bottomInset,
            )
        }
        dialog.setOnDismissListener { onDismissed?.invoke() }
    }

    fun show() = dialog.show()
}

// ==================================================================
// 歌词库
// ==================================================================
class LibrarySheet(
    private val activity: Activity,
    private val app: LyricCarApp,
    private val downloads: DownloadsRepository,
) {
    private val sheet: SheetDialog
    private lateinit var countText: TextView
    private lateinit var exportButton: Button
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var clearRow: LinearLayout
    private lateinit var adapter: SongAdapter

    private val storeListener: () -> Unit = {
        activity.runOnUiThread { refresh() }
    }

    init {
        sheet = SheetDialog(activity, "歌词库", onDismissed = {
            app.store.removeOnChangedListener(storeListener)
        })
        build()
        app.store.addOnChangedListener(storeListener)
    }

    fun show() = sheet.show()

    private fun build() {
        val body = sheet.body
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countText = textView(activity, "0 首", 18, Palette.TEXT_PRIMARY, bold = true)
        top.addView(countText)
        top.addView(LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        exportButton = textButton(activity, "导出到下载") { export() }
        top.addView(exportButton)
        top.addView(textButton(activity, "从下载导入") {
            sheet.dialog.dismiss()
            ImportSheet(activity, app, downloads).show()
        })
        top.addView(textButton(activity, "局域网传输") {
            sheet.dialog.dismiss()
            LanSheet(activity, app).show()
        })
        body.addView(top, matchWidthWrap())
        body.addView(space(activity, 8))

        body.addView(
            textView(
                activity,
                "歌词是这台车听出来的。「导出到下载」会存进系统「下载」目录；" +
                    "手机和车机连同一个 Wi-Fi 时，用「局域网传输」直接传最省事。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.4f) },
            matchWidthWrap(),
        )
        body.addView(space(activity, 12))

        val prunedCount = app.consumePruneCount()
        if (prunedCount > 0) {
            body.addView(
                textView(
                    activity,
                    "启动时顺手清掉了 $prunedCount 首太短的记录（不到 5 句，或只覆盖了 10 秒以内）" +
                        "—— 多半是列表里滑过去、没完整听的歌。",
                    14,
                    Palette.TEXT_SECONDARY,
                ).apply { setLineSpacing(0f, 1.4f) },
                matchWidthWrap(),
            )
            body.addView(space(activity, 10))
        }

        val metrics = activity.resources.displayMetrics
        list = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                (metrics.heightPixels * 0.45f).toInt(),
            )
        }
        adapter = SongAdapter(
            activity = activity,
            onDelete = { song -> delete(song) },
            onPreview = { song -> preview(song) },
        )
        list.adapter = adapter
        list.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        body.addView(list, matchWidthWrap())

        emptyText = textView(
            activity,
            "还没有采集到歌词。\n放一首歌，让它唱完一遍试试。",
            18,
            Palette.TEXT_SECONDARY,
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.6f)
            setPaddingDp(16, 36, 16, 36)
            visibility = View.GONE
        }
        body.addView(emptyText, matchWidthWrap())

        clearRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        clearRow.addView(textButton(activity, "清空歌词库") { confirmClear() })
        body.addView(clearRow, matchWidthWrap())

        refresh()
    }

    private fun refresh() {
        val songs = app.store.songs()
        adapter.submit(songs)
        countText.text = "${songs.size} 首"
        exportButton.isEnabled = songs.isNotEmpty()
        exportButton.alpha = if (songs.isNotEmpty()) 1f else 0.35f
        list.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        clearRow.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun export() {
        val json = app.store.exportJson()
        downloads.save(lyricExportFileName(), json) { path, error ->
            if (error != null) {
                toast(activity, "导出失败：$error", true)
            } else {
                toast(activity, "已导出到「下载」：$path", true)
            }
        }
    }

    private fun delete(song: StoredSong) {
        app.store.remove(song.key)
        app.refreshEngine()
    }

    private fun preview(song: StoredSong) {
        val message = song.lines.joinToString("\n") { line ->
            String.format(Locale.US, "%.1f s    %s", line.timeMs / 1000.0, line.text)
        }
        AlertDialog.Builder(activity, alertDialogTheme())
            .setTitle(song.displayName)
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(activity, alertDialogTheme())
            .setTitle("清空歌词库？")
            .setMessage("所有已采集的歌词都会被删除，且无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                app.store.clear()
                app.refreshEngine()
            }
            .show()
    }

    private class SongAdapter(
        private val activity: Activity,
        private val onDelete: (StoredSong) -> Unit,
        private val onPreview: (StoredSong) -> Unit,
    ) : BaseAdapter() {
        private var songs: List<StoredSong> = emptyList()

        fun submit(value: List<StoredSong>) {
            songs = value
            notifyDataSetChanged()
        }

        override fun getCount(): Int = songs.size
        override fun getItem(position: Int): StoredSong = songs[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val song = songs[position]
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingDp(0, 10, 0, 10)
                isClickable = true
            }
            val icon = textView(
                activity,
                if (song.ready) "✓" else "○",
                22,
                if (song.ready) Palette.ACCENT else Palette.TEXT_SECONDARY,
            )
            row.addView(icon, LinearLayout.LayoutParams(activity.dp(34), LayoutParams.WRAP_CONTENT))

            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            column.addView(
                textView(activity, song.displayName, 18, Palette.TEXT_PRIMARY).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            val subtitle = listOfNotNull(
                song.artist.ifEmpty { null },
                "${song.lines.size} 句",
                if (song.ready) "完整" else if (!song.fromStart) "缺开头" else "未采完",
                song.source.label,
            ).joinToString(" · ")
            column.addView(
                textView(activity, subtitle, 14, Palette.TEXT_SECONDARY).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            row.addView(column, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(textButton(activity, "删除") { onDelete(song) })
            row.setOnClickListener { onPreview(song) }
            return row
        }
    }
}

// ==================================================================
// 从「下载」导入
// ==================================================================
class ImportSheet(
    private val activity: Activity,
    private val app: LyricCarApp,
    private val downloads: DownloadsRepository,
) {
    private val sheet = SheetDialog(activity, "从「下载」导入")
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var adapter: FileAdapter
    private lateinit var refreshButton: Button

    fun show() {
        build()
        sheet.show()
    }

    private fun build() {
        val body = sheet.body
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            textView(activity, "从「下载」导入", 20, Palette.TEXT_PRIMARY, bold = true),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        refreshButton = textButton(activity, "刷新") { reload() }
        top.addView(refreshButton)
        body.addView(top, matchWidthWrap())
        body.addView(space(activity, 4))

        status = textView(
            activity,
            "这里有下载目录里所有 .json 文件。换车机时先把导出的文件拷进来。",
            14,
            Palette.TEXT_SECONDARY,
        ).apply { setLineSpacing(0f, 1.4f) }
        body.addView(status, matchWidthWrap())
        body.addView(space(activity, 12))

        val metrics = activity.resources.displayMetrics
        list = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                (metrics.heightPixels * 0.5f).toInt(),
            )
        }
        adapter = FileAdapter(
            activity = activity,
            onImport = { file -> importFile(file) },
            onDelete = { file -> deleteFile(file) },
        )
        list.adapter = adapter
        list.setOnTouchListener { view, _ ->
            view.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        body.addView(list, matchWidthWrap())
        reload()
    }

    private fun reload() {
        status.text = "正在读取「下载」目录…"
        status.setTextColor(Palette.TEXT_SECONDARY)
        refreshButton.isEnabled = false
        downloads.list { files, error ->
            refreshButton.isEnabled = true
            if (!sheet.dialog.isShowing) return@list
            if (error != null) {
                status.text = "读不到「下载」目录：$error"
                status.setTextColor(Palette.ERROR)
                adapter.submit(emptyList())
                return@list
            }
            val values = files.orEmpty()
            adapter.submit(values)
            if (values.isEmpty()) {
                status.text = "下载目录里还没有 .json 文件。先在歌词库面板点「导出」，就会出现在这里。"
                status.setTextColor(Palette.TEXT_SECONDARY)
            } else {
                status.text = "这里有下载目录里所有 .json 文件。换车机时先把导出的文件拷进来。"
                status.setTextColor(Palette.TEXT_SECONDARY)
            }
        }
    }

    private fun importFile(file: DownloadedFile) {
        status.text = "正在读取《${file.name}》…"
        downloads.read(file) { text, error ->
            if (!sheet.dialog.isShowing) return@read
            if (error != null || text == null) {
                status.text = "导入失败：${error ?: "读不到文件"}"
                status.setTextColor(Palette.ERROR)
                return@read
            }
            val result = app.store.importJson(text)
            app.refreshEngine()
            sheet.dialog.dismiss()
            toast(activity, "《${file.name}》${result.summary}", true)
        }
    }

    private fun deleteFile(file: DownloadedFile) {
        downloads.delete(file) { _, error ->
            if (error != null) {
                toast(activity, "删除失败：$error", true)
            } else {
                reload()
            }
        }
    }

    private class FileAdapter(
        private val activity: Activity,
        private val onImport: (DownloadedFile) -> Unit,
        private val onDelete: (DownloadedFile) -> Unit,
    ) : BaseAdapter() {
        private var files: List<DownloadedFile> = emptyList()

        fun submit(value: List<DownloadedFile>) {
            files = value
            notifyDataSetChanged()
        }

        override fun getCount(): Int = files.size
        override fun getItem(position: Int): DownloadedFile = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val file = files[position]
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingDp(0, 8, 0, 8)
            }
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(
                textView(activity, file.name, 17, Palette.TEXT_PRIMARY).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            column.addView(
                textView(activity, "${sizeText(file.size)} · ${timeText(file.modifiedMs)}", 13, Palette.TEXT_SECONDARY),
            )
            row.addView(column, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(filledButton(activity, "导入") { onImport(file) })
            row.addView(textButton(activity, "删除") { onDelete(file) })
            return row
        }

        private fun sizeText(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        }

        private fun timeText(ms: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(java.util.Date(ms))
    }
}

// ==================================================================
// 设置
// ==================================================================
class SettingsSheet(
    private val activity: Activity,
    private val app: LyricCarApp,
    private val downloads: DownloadsRepository,
) {
    private val sheet = SheetDialog(activity, "设置")
    private lateinit var styleSection: LinearLayout
    private lateinit var backgroundSection: LinearLayout

    fun show() {
        rebuild()
        sheet.show()
    }

    private fun rebuild() {
        val body = sheet.body
        body.removeAllViews()

        body.addView(
            textView(activity, "歌词写在", 18, Palette.TEXT_SECONDARY),
        )
        body.addView(space(activity, 10))

        for (field in LyricFieldPreference.entries) {
            val selected = app.settings.field == field
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(
                    if (selected) withAlpha(Palette.ACCENT, 40) else Palette.SURFACE_LIGHT,
                    12,
                )
                setPaddingDp(18, 14, 18, 14)
                isClickable = true
                setOnClickListener {
                    app.setField(field)
                    rebuild()
                }
            }
            row.addView(
                textView(
                    activity,
                    if (selected) "◉" else "○",
                    22,
                    if (selected) Palette.ACCENT else Palette.TEXT_SECONDARY,
                ),
            )
            row.addView(space(activity, 0).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(14), 1)
            })
            row.addView(textView(activity, field.label, 18, Palette.TEXT_PRIMARY))
            body.addView(row, matchWidthWrap())
            body.addView(space(activity, 8))
        }

        body.addView(space(activity, 4))
        body.addView(
            textView(
                activity,
                "不确定就留「自动判断」：App 会看着标题、歌手、专辑三行，自己认歌词写在\n" +
                    "哪一行 —— 哔哩音乐这类写在歌手行的，和网易云 / QQ 音乐这类写在歌曲名行\n" +
                    "的，都能认出来。\n" +
                    "真认不出来时再手动指定 —— 左侧面板能直接看到这三行的原始内容，\n" +
                    "歌词写在哪一行一目了然。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.5f) },
            matchWidthWrap(),
        )

        body.addView(space(activity, 22))
        body.addView(textView(activity, "外观", 18, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 10))

        for (mode in ThemeMode.entries) {
            val selected = app.settings.theme == mode
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(
                    if (selected) withAlpha(Palette.ACCENT, 40) else Palette.SURFACE_LIGHT,
                    12,
                )
                setPaddingDp(18, 14, 18, 14)
                isClickable = true
                setOnClickListener {
                    if (app.settings.theme == mode) return@setOnClickListener
                    // 先关面板再切主题：Activity 会整个重建，旧 Dialog 不关会泄漏。
                    sheet.dialog.dismiss()
                    app.setTheme(mode)
                }
            }
            row.addView(
                textView(
                    activity,
                    if (selected) "◉" else "○",
                    22,
                    if (selected) Palette.ACCENT else Palette.TEXT_SECONDARY,
                ),
            )
            row.addView(space(activity, 0).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(14), 1)
            })
            row.addView(textView(activity, mode.label, 18, Palette.TEXT_PRIMARY))
            body.addView(row, matchWidthWrap())
            body.addView(space(activity, 8))
        }

        body.addView(space(activity, 4))
        body.addView(
            textView(
                activity,
                "浅色适合白天或反光强的车机；切换后界面会重建一次，正在采集的歌词不受影响。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.5f) },
            matchWidthWrap(),
        )

        body.addView(space(activity, 22))
        body.addView(textView(activity, "歌词样式", 18, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 10))
        styleSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(styleSection, matchWidthWrap())
        fillStyleSection()

        body.addView(space(activity, 22))
        body.addView(textView(activity, "背景", 18, Palette.TEXT_SECONDARY))
        body.addView(space(activity, 10))
        backgroundSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(backgroundSection, matchWidthWrap())
        fillBackgroundSection()

        body.addView(space(activity, 22))

        val switchRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switchColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        switchColumn.addView(textView(activity, "歌词界面保持常亮", 18, Palette.TEXT_PRIMARY))
        switchColumn.addView(
            textView(activity, "看歌词时不自动熄屏（只是不熄屏，不额外耗电）", 14, Palette.TEXT_SECONDARY),
        )
        switchRow.addView(switchColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val switch = Switch(activity).apply {
            isChecked = app.settings.keepScreenOn
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            trackTintList = ColorStateList.valueOf(withAlpha(Palette.ACCENT, 90))
            setOnCheckedChangeListener { _, value -> app.setKeepScreenOn(value) }
        }
        switchRow.addView(switch)
        body.addView(switchRow, matchWidthWrap())

        body.addView(space(activity, 16))
        body.addView(dividerLine(activity))
        body.addView(space(activity, 16))

        val diagnosticsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Palette.SURFACE_LIGHT, 12)
            setPaddingDp(18, 14, 18, 14)
            isClickable = true
            setOnClickListener {
                sheet.dialog.dismiss()
                DiagnosticsSheet(activity, app, downloads).show()
            }
        }
        diagnosticsRow.addView(textView(activity, "≡", 22, Palette.ACCENT))
        diagnosticsRow.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(14), 1)
        })
        val diagColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        diagColumn.addView(textView(activity, "诊断日志", 18, Palette.TEXT_PRIMARY))
        diagColumn.addView(
            textView(
                activity,
                "换了车机不出歌词时，导出来发我 —— 有系统版本和原始元数据",
                14,
                Palette.TEXT_SECONDARY,
            ),
        )
        diagnosticsRow.addView(diagColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(diagnosticsRow, matchWidthWrap())
    }

    // ------------------------------------------------------
    // 歌词样式
    // ------------------------------------------------------
    private fun fillStyleSection() {
        val display = app.settings.lyric
        styleSection.removeAllViews()

        styleSection.addView(stylePreview(display), matchWidthWrap())
        styleSection.addView(space(activity, 14))

        styleSection.addView(styleLabel("字号"))
        styleSection.addView(space(activity, 8))
        styleSection.addView(
            chipRow(
                LyricFontScale.entries.map { font ->
                    chip(font.label, display.font == font) {
                        pick(display.copy(font = font))
                    }
                },
            ),
            matchWidthWrap(),
        )

        styleSection.addView(space(activity, 12))
        styleSection.addView(styleLabel("行距"))
        styleSection.addView(space(activity, 8))
        styleSection.addView(
            chipRow(
                LyricLineSpacing.entries.map { spacing ->
                    chip(spacing.label, display.spacing == spacing) {
                        pick(display.copy(spacing = spacing))
                    }
                },
            ),
            matchWidthWrap(),
        )

        styleSection.addView(space(activity, 12))
        styleSection.addView(styleLabel("对齐"))
        styleSection.addView(space(activity, 8))
        styleSection.addView(
            chipRow(
                LyricAlignment.entries.map { alignment ->
                    chip(alignment.label, display.alignment == alignment) {
                        pick(display.copy(alignment = alignment))
                    }
                },
            ),
            matchWidthWrap(),
        )

        styleSection.addView(space(activity, 12))
        styleSection.addView(styleLabel("当前句颜色"))
        styleSection.addView(space(activity, 8))
        styleSection.addView(colorRow(display), matchWidthWrap())
        styleSection.addView(space(activity, 6))
        styleSection.addView(
            textView(
                activity,
                "颜色只作用于正在唱的那一句 · 当前：${display.color.label}",
                13,
                Palette.TEXT_SECONDARY,
            ),
        )
    }

    /** 改一项就重画这一小段，不重建整个面板，滚动位置不会跳。 */
    private fun pick(display: LyricDisplay) {
        app.setLyricDisplay(display)
        fillStyleSection()
    }

    /** 用真正的歌词控件做预览：字号 / 行距 / 对齐 / 颜色都是真实效果。 */
    private fun stylePreview(display: LyricDisplay): View {
        val preview = LyricsView(activity).apply {
            interactive = false
            applyStyle(lyricStyleOf(display))
            setLines(PREVIEW_LINES, PREVIEW_CURRENT)
        }
        return FrameLayout(activity).apply {
            background = roundedBackground(Palette.SURFACE_LIGHT, 12)
            addView(preview, LayoutParams.MATCH_PARENT, activity.dp(PREVIEW_HEIGHT_DP))
        }
    }

    // ------------------------------------------------------
    // 背景
    // ------------------------------------------------------
    private fun fillBackgroundSection() {
        val bg = app.settings.background
        backgroundSection.removeAllViews()

        backgroundSection.addView(
            textView(
                activity,
                when (bg.mode) {
                    BackgroundMode.DEFAULT -> "当前：默认背景"
                    BackgroundMode.IMAGE -> "当前：自定义图片"
                    BackgroundMode.COLOR -> "当前：纯色 · ${bg.color.label}"
                },
                14,
                Palette.TEXT_SECONDARY,
            ),
        )
        backgroundSection.addView(space(activity, 10))
        backgroundSection.addView(
            outlinedButton(activity, "从图库选择图片", hPadding = 18) {
                val host = activity as? MainActivity
                if (host == null) {
                    toast(activity, "当前界面无法选择图片")
                } else {
                    sheet.dialog.dismiss()
                    host.pickBackgroundImage()
                }
            },
            matchWidthWrap(),
        )

        backgroundSection.addView(space(activity, 12))
        backgroundSection.addView(styleLabel("左栏底色（自定义图片时生效）"))
        backgroundSection.addView(space(activity, 8))
        backgroundSection.addView(
            chipRow(
                PanelTone.entries.map { tone ->
                    chip(tone.label, bg.panel == tone) {
                        app.setPanelTone(tone)
                        fillBackgroundSection()
                    }
                },
            ),
            matchWidthWrap(),
        )
        if (bg.mode == BackgroundMode.IMAGE) {
            backgroundSection.addView(space(activity, 6))
            backgroundSection.addView(
                textView(
                    activity,
                    if (bg.panel == PanelTone.TRANSPARENT) {
                        "左栏整块透明，背景图直接露出来，只留中间那条可拖动的分栏条。"
                    } else {
                        "左栏铺一层半透明底，图片再花也看得清文字；嫌不好看就换「透明」。"
                    },
                    13,
                    Palette.TEXT_SECONDARY,
                ),
            )
        }

        backgroundSection.addView(space(activity, 12))
        backgroundSection.addView(styleLabel("纯色"))
        backgroundSection.addView(space(activity, 8))
        backgroundSection.addView(backgroundColors(bg), matchWidthWrap())

        if (bg.imageName.isNotEmpty() && bg.mode != BackgroundMode.IMAGE) {
            backgroundSection.addView(space(activity, 8))
            backgroundSection.addView(
                textButton(activity, "用回上次的图片") {
                    app.setBackground(bg.copy(mode = BackgroundMode.IMAGE, imageName = bg.imageName))
                    fillBackgroundSection()
                },
            )
        }

        if (bg.mode != BackgroundMode.DEFAULT) {
            backgroundSection.addView(space(activity, 8))
            backgroundSection.addView(
                outlinedButton(activity, "恢复默认背景", hPadding = 18) {
                    app.resetBackground()
                    fillBackgroundSection()
                },
                matchWidthWrap(),
            )
        }
    }

    private fun backgroundColors(bg: BackgroundStyle): LinearLayout {
        val light = Palette.mode == ThemeMode.LIGHT
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        BackgroundColorPreset.entries.forEach { preset ->
            val selected = bg.mode == BackgroundMode.COLOR && bg.color == preset
            val swatch = View(activity).apply {
                background = roundedBackground(
                    preset.colorFor(light),
                    999,
                    if (selected) Palette.TEXT_PRIMARY else Palette.STROKE,
                    if (selected) 2 else 1,
                )
                layoutParams = LinearLayout.LayoutParams(activity.dp(40), activity.dp(40))
                contentDescription = preset.label
                isClickable = true
                setOnClickListener {
                    app.setBackground(
                        bg.copy(mode = BackgroundMode.COLOR, color = preset),
                    )
                    fillBackgroundSection()
                }
            }
            row.addView(swatch)
            row.addView(space(activity, 0).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
            })
        }
        return row
    }

    private fun styleLabel(text: String): TextView =
        textView(activity, text, 14, Palette.TEXT_SECONDARY)

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

    private fun colorRow(display: LyricDisplay): LinearLayout {
        val lightTheme = Palette.mode == ThemeMode.LIGHT
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        LyricColorPreset.entries.forEach { preset ->
            val selected = display.color == preset
            val color = if (preset == LyricColorPreset.THEME) {
                Palette.ACCENT
            } else {
                preset.colorFor(lightTheme)
            }
            val swatch = View(activity).apply {
                background = roundedBackground(
                    color,
                    999,
                    if (selected) Palette.TEXT_PRIMARY else Color.TRANSPARENT,
                    if (selected) 2 else 1,
                )
                layoutParams = LinearLayout.LayoutParams(activity.dp(40), activity.dp(40))
                contentDescription = preset.label
                isClickable = true
                setOnClickListener { pick(display.copy(color = preset)) }
            }
            row.addView(swatch)
            row.addView(space(activity, 0).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
            })
        }
        return row
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val PREVIEW_HEIGHT_DP = 176
        const val PREVIEW_CURRENT = 1
        val PREVIEW_LINES = listOf(
            LyricLine(0, "这一句已经唱过"),
            LyricLine(1_000, "这一句正在唱"),
            LyricLine(2_000, "这一句还没唱"),
        )
    }
}

// ==================================================================
// 关于
// ==================================================================
class AboutSheet(private val activity: Activity) {

    private val sheet = SheetDialog(activity, "关于")

    fun show() {
        rebuild()
        sheet.show()
    }

    private fun rebuild() {
        val body = sheet.body
        body.removeAllViews()

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageView(activity).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(activity.dp(64), activity.dp(64)),
        )
        header.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(18), 1)
        })
        val titleColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(
            textView(activity, "LyricCar 歌词", 24, Palette.TEXT_PRIMARY, bold = true),
        )
        titleColumn.addView(space(activity, 6))
        titleColumn.addView(textView(activity, versionLine(), 15, Palette.TEXT_SECONDARY))
        header.addView(titleColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        body.addView(header, matchWidthWrap())

        body.addView(space(activity, 22))
        body.addView(field("开发者", "intersoul"))
        body.addView(space(activity, 14))
        body.addView(field("版权所有", "© ${currentYear()} intersoul，保留所有权利"))
        body.addView(space(activity, 14))

        val githubRow = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Palette.SURFACE_LIGHT, 12)
            setPaddingDp(18, 14, 18, 14)
            isClickable = true
            contentDescription = "打开 GitHub 仓库"
            setOnClickListener { openGithub() }
        }
        githubRow.addView(textView(activity, "GitHub 仓库", 14, Palette.TEXT_SECONDARY))
        githubRow.addView(space(activity, 4))
        githubRow.addView(textView(activity, GITHUB_URL, 16, Palette.ACCENT))
        body.addView(githubRow, matchWidthWrap())

        // 两个按钮放在说明文字前面：车机底部任务栏会盖住面板底部，
        // 主要操作要一眼能看到，说明文字可以往下滚。
        body.addView(space(activity, 16))
        body.addView(
            filledButton(activity, "在浏览器中打开") { openGithub() },
            matchWidthWrap(),
        )
        body.addView(space(activity, 10))
        body.addView(
            outlinedButton(activity, "复制 GitHub 链接") { copyGithub() },
            matchWidthWrap(),
        )

        body.addView(space(activity, 18))
        body.addView(dividerLine(activity))
        body.addView(space(activity, 16))
        body.addView(
            textView(
                activity,
                "车机全屏歌词：只读系统媒体会话，不接管音频、不收集数据、不连外网。\n" +
                    "歌词库、设置和诊断日志都只存在车机本地。\n\n" +
                    "遇到问题请带上「设置 → 诊断日志」里的日志到 GitHub 提 issue。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.55f) },
            matchWidthWrap(),
        )
        body.addView(space(activity, 12))
        body.addView(
            textView(
                activity,
                "本应用为独立第三方应用，与比亚迪汽车及其关联公司无关。",
                14,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.5f) },
            matchWidthWrap(),
        )
    }

    private fun field(label: String, value: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView(activity, label, 14, Palette.TEXT_SECONDARY))
            addView(space(activity, 4))
            addView(textView(activity, value, 18, Palette.TEXT_PRIMARY))
        }

    private fun versionLine(): String =
        "v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）· ${BuildConfig.BUILD_TYPE}"

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private fun openGithub() {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            copyGithub()
            toast(activity, "这台车机没有浏览器，链接已复制")
        }
    }

    private fun copyGithub() {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LyricCar GitHub", GITHUB_URL))
        toast(activity, "GitHub 链接已复制")
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/intersoul/LyricCar"
    }
}

// ==================================================================
// 诊断日志
// ==================================================================
class DiagnosticsSheet(
    private val activity: Activity,
    private val app: LyricCarApp,
    private val downloads: DownloadsRepository?,
) {
    private val sheet = SheetDialog(activity, "诊断日志")
    private lateinit var status: TextView
    private lateinit var logView: TextView

    fun show() {
        build()
        sheet.show()
    }

    private fun build() {
        val body = sheet.body
        body.addView(
            textView(
                activity,
                "换了车机发现不出歌词时，按这两步做，把日志发给我就能定位：\n" +
                    "① 先随便播一首歌、放半分钟（让日志里留下播放器发出来的东西）；\n" +
                    "② 回来点「导出到下载」，把下载目录里的 .txt 发我。\n" +
                    "日志里只有系统版本、歌名、歌手和播放状态，没有账号密码。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.6f) },
            matchWidthWrap(),
        )
        body.addView(space(activity, 16))

        status = textView(activity, "", 15, Palette.ACCENT)
        body.addView(status, matchWidthWrap())
        body.addView(space(activity, 12))

        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val export = filledButton(activity, "导出到下载") { export() }
        export.isEnabled = downloads != null
        actions.addView(export)
        actions.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        actions.addView(outlinedButton(activity, "用手机来取") {
            sheet.dialog.dismiss()
            LanSheet(activity, app).show()
        })
        body.addView(actions, matchWidthWrap())
        body.addView(space(activity, 10))

        val actions2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(outlinedButton(activity, "复制全部") { copyAll() })
        actions2.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        actions2.addView(textButton(activity, "清空") { clear() })
        body.addView(actions2, matchWidthWrap())

        body.addView(space(activity, 16))
        body.addView(
            textView(activity, "最近日志（导出的是完整文件）：", 13, Palette.TEXT_SECONDARY),
        )
        body.addView(space(activity, 6))

        logView = textView(activity, "", 11, Palette.LOG_TEXT).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.45f)
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(activity).apply {
            background = roundedBackground(Palette.SURFACE_LIGHT, 12, Palette.STROKE, 1)
            setPaddingDp(12, 12, 12, 12)
            addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                activity.dp(220),
            )
            setOnTouchListener { view, _ ->
                view.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        body.addView(logScroll, matchWidthWrap())
        refreshLog()
        if (downloads == null) {
            status.text = "当前面板没有下载权限入口，可用「用手机来取」或「复制全部」。"
        }
    }

    private fun refreshLog() {
        val lines = app.diagnostics.recent()
        logView.text = if (lines.isEmpty()) {
            "还没有日志"
        } else {
            lines.asReversed().joinToString("\n")
        }
    }

    private fun export() {
        val repository = downloads ?: return
        status.text = "正在导出…"
        Thread {
            val text = app.diagnostics.snapshot()
            activity.runOnUiThread {
                repository.save(diagnosticsFileName(), text) { path, error ->
                    status.text = if (error != null) "导出失败：$error" else "已存到「下载」目录：$path"
                    status.setTextColor(if (error != null) Palette.ERROR else Palette.ACCENT)
                }
            }
        }.start()
    }

    private fun copyAll() {
        Thread {
            val text = app.diagnostics.snapshot()
            activity.runOnUiThread {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("LyricCar 诊断日志", text))
                status.text = "已复制到剪贴板"
                status.setTextColor(Palette.ACCENT)
            }
        }.start()
    }

    private fun clear() {
        status.text = "正在清空…"
        Thread {
            app.diagnostics.clear()
            activity.runOnUiThread {
                status.text = "日志已清空"
                status.setTextColor(Palette.ACCENT)
                refreshLog()
            }
        }.start()
    }
}

// ==================================================================
// 局域网传输
// ==================================================================
class LanSheet(
    private val activity: Activity,
    private val app: LyricCarApp,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var sheet: SheetDialog
    private lateinit var status: TextView
    private lateinit var addressColumn: LinearLayout
    private lateinit var countText: TextView
    private var server: LyricLanServer? = null
    private var closed = false

    fun show() {
        sheet = SheetDialog(activity, "局域网传输", onDismissed = { shutdown() })
        build()
        sheet.show()
        startServer()
    }

    private fun build() {
        val body = sheet.body
        body.addView(
            textView(
                activity,
                "让手机连上和车机「同一个」Wi-Fi / 热点，然后在手机浏览器里打开下面这个地址。\n" +
                    "页面里可以下载车上的歌词库、上传手机里的歌词库，也可以把诊断日志带走。",
                15,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.6f) },
            matchWidthWrap(),
        )
        body.addView(space(activity, 16))

        status = textView(activity, "正在启动…", 15, Palette.TEXT_SECONDARY)
        body.addView(status, matchWidthWrap())
        body.addView(space(activity, 10))

        addressColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(addressColumn, matchWidthWrap())
        body.addView(space(activity, 8))

        countText = textView(activity, "", 15, Palette.TEXT_SECONDARY)
        body.addView(countText, matchWidthWrap())
        body.addView(space(activity, 18))

        body.addView(
            textView(
                activity,
                "注意：\n" +
                    "· 传输期间别退出这个 App（页面就是它在提供）；\n" +
                    "· 关掉这个面板就会停掉服务，不会一直开着端口；\n" +
                    "· 地址里的 IP 每个网络都可能不一样，换网络后重新打开面板看一眼。",
                13,
                Palette.TEXT_SECONDARY,
            ).apply { setLineSpacing(0f, 1.7f) },
            matchWidthWrap(),
        )
    }

    private fun startServer() {
        val instance = LyricLanServer(
            store = app.store,
            logProvider = { app.diagnostics.snapshot() },
        )
        server = instance
        Thread({
            try {
                instance.start()
                activity.runOnUiThread {
                    if (closed) {
                        instance.stop()
                        return@runOnUiThread
                    }
                    showAddresses()
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    status.text = "起服务失败：${error.message}"
                    status.setTextColor(Palette.ERROR)
                }
            }
        }, "lyriccar-lan-start").start()

        mainHandler.post(object : Runnable {
            override fun run() {
                if (closed) return
                countText.text = if (server == null) "" else {
                    val handled = server!!.requestCount
                    if (handled == 0) {
                        "还没收到请求。手机浏览器里打开上面这个地址就行。"
                    } else {
                        "已经处理 $handled 个请求 —— 手机连上来了。"
                    }
                }
                countText.setTextColor(
                    if ((server?.requestCount ?: 0) == 0) Palette.TEXT_SECONDARY else Palette.ACCENT,
                )
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun showAddresses() {
        val instance = server ?: return
        val urls = instance.urls
        addressColumn.removeAllViews()
        if (urls.isEmpty()) {
            status.text = "没找到局域网地址：车机可能没连 Wi-Fi / 热点。连上后再打开这个面板。"
            status.setTextColor(Palette.WARNING)
            return
        }
        status.text = "已启动。在手机浏览器里打开："
        status.setTextColor(Palette.ACCENT)

        val copy = filledButton(activity, "复制地址") {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LyricCar", urls.first()))
            toast(activity, "已复制：${urls.first()}")
        }
        addressColumn.addView(copy)
        addressColumn.addView(space(activity, 12))
        for (url in urls) {
            addressColumn.addView(
                textView(activity, url, 22, Palette.ACCENT, bold = true).apply {
                    setTextIsSelectable(true)
                    setPaddingDp(0, 4, 0, 4)
                },
                matchWidthWrap(),
            )
        }
    }

    private fun shutdown() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
    }
}
