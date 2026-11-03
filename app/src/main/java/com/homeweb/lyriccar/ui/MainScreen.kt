package com.homeweb.lyriccar.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.homeweb.lyriccar.LyricCarApp
import com.homeweb.lyriccar.data.AppSettings
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.LyricMode
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.transfer.DownloadsRepository
import java.util.Locale

/** 主界面：左「正在播放 / 控制」，右整块歌词。 */
class MainScreen(
    private val activity: Activity,
    private val app: LyricCarApp,
    private val downloads: DownloadsRepository,
) : LinearLayout(activity) {

    private val cover = RoundedImageView(activity)
    private val coverPlaceholder = centeredText(activity, "♪", 48, Palette.TEXT_SECONDARY)
    private val titleText: TextView
    private val artistText: TextView
    private val albumText: TextView
    private val sourceChip: TextView
    private val positionText: TextView
    private val durationText: TextView
    private val seekBar: SeekBar
    private val prevButton: ImageButton
    private val playButton: ImageButton
    private val nextButton: ImageButton
    private val statusText: TextView

    private val banner: TextView
    private val lyricsView = LyricsView(activity)
    private val waitingText: TextView
    private val contentColumn: LinearLayout
    private val idleScroll: ScrollView
    private val idleColumn: LinearLayout

    private var lastState = LyricSessionState()
    private var lastAccess: Boolean? = null
    private var lastBrowserAvailable = false
    private var dragging = false
    private var lastArtUri = ""
    private var lastArtBytes: ByteArray? = null

    private val compact = resources.configuration.screenWidthDp < 640
    private val leftPanel: LinearLayout
    private val rightPanel: FrameLayout
    private val splitHandle: SplitHandle
    private var splitFraction = AppSettings.DEFAULT_SPLIT_RATIO

    init {
        setBackgroundColor(Palette.BACKGROUND)
        orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        // ------------------------------------------------------
        // 左面板
        // ------------------------------------------------------
        leftPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = leftPanelBackground()
        }
        val leftScroll = ScrollView(activity).apply { isFillViewport = true }
        val leftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(28, 28, 28, 28)
        }
        leftScroll.addView(
            leftColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        leftPanel.addView(leftScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val coverFrame = FrameLayout(activity).apply {
            background = roundedBackground(Palette.SURFACE, 16)
            addView(
                coverPlaceholder,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            cover.visibility = View.GONE
            addView(cover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        topRow.addView(
            coverFrame,
            LinearLayout.LayoutParams(activity.dp(132), activity.dp(132)),
        )

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = textView(activity, "未在播放", 24, Palette.TEXT_PRIMARY, bold = true).apply {
            // 歌词写在歌曲名行时，这里就是当前这句歌词：允许换行，别再把第二行切掉。
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            minHeight = activity.dp(60)
        }
        artistText = textView(activity, "歌手：—", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        albumText = textView(activity, "专辑：—", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        sourceChip = textView(activity, "", 13, Palette.ACCENT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPaddingDp(12, 6, 12, 6)
            background = roundedBackground(withAlpha(Palette.ACCENT, 36), 999)
            visibility = View.GONE
        }
        textColumn.addView(
            titleText,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )
        textColumn.addView(space(activity, 10))
        textColumn.addView(artistText)
        textColumn.addView(space(activity, 4))
        textColumn.addView(albumText)
        textColumn.addView(space(activity, 12))
        textColumn.addView(
            sourceChip,
            LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )
        topRow.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(20), 1)
        })
        topRow.addView(
            textColumn,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        leftColumn.addView(topRow, matchWidthWrap())

        leftColumn.addView(space(activity, 26))

        // 进度条 + 时间
        val progressColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        seekBar = SeekBar(activity).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.TRACK)
            thumbTintList = ColorStateList.valueOf(Palette.ACCENT)
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
        }
        positionText = textView(activity, "0:00", 15, Palette.TEXT_PRIMARY)
        durationText = textView(activity, "--:--", 15, Palette.TEXT_PRIMARY)
        val timeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPaddingDp(4, 0, 4, 0)
        }
        timeRow.addView(
            positionText,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        timeRow.addView(durationText)
        progressColumn.addView(seekBar, matchWidthWrap())
        progressColumn.addView(timeRow, matchWidthWrap())
        leftColumn.addView(progressColumn, matchWidthWrap())

        leftColumn.addView(space(activity, 10))

        // 播放控制
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        prevButton = mediaButton(android.R.drawable.ic_media_previous, 42) {
            app.repository.previous()
        }
        playButton = mediaButton(android.R.drawable.ic_media_play, 50) {
            if (lastState.playingNow) app.repository.pause() else app.repository.play()
        }
        nextButton = mediaButton(android.R.drawable.ic_media_next, 42) {
            app.repository.next()
        }
        controls.addView(prevButton, LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)))
        controls.addView(space(activity, 12).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(16), 1)
        })
        controls.addView(playButton, LinearLayout.LayoutParams(activity.dp(62), activity.dp(62)))
        controls.addView(space(activity, 12).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(16), 1)
        })
        controls.addView(nextButton, LinearLayout.LayoutParams(activity.dp(52), activity.dp(52)))
        leftColumn.addView(controls, matchWidthWrap())

        leftColumn.addView(space(activity, 18))

        statusText = textView(activity, "歌词库：0 首", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftColumn.addView(statusText, matchWidthWrap())

        leftColumn.addView(space(activity, 18))

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val libraryButton = outlinedButton(activity, "歌词库", hPadding = 8) {
            showLibrarySheet()
        }
        val settingsButton = outlinedButton(activity, "设置", hPadding = 8) {
            showSettingsSheet()
        }
        val aboutButton = outlinedButton(activity, "关于", hPadding = 8) {
            showAboutSheet()
        }
        val buttonParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        buttonRow.addView(libraryButton, buttonParams)
        buttonRow.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        buttonRow.addView(
            settingsButton,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        buttonRow.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        buttonRow.addView(aboutButton, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        leftColumn.addView(buttonRow, matchWidthWrap())

        // ------------------------------------------------------
        // 右面板
        // ------------------------------------------------------
        rightPanel = FrameLayout(activity).apply { setBackgroundColor(Palette.BACKGROUND) }

        contentColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        banner = textView(activity, "", 15, Palette.ACCENT).apply {
            visibility = View.GONE
            setLineSpacing(0f, 1.35f)
            setPaddingDp(18, 12, 18, 12)
            background = roundedBackground(withAlpha(Palette.ACCENT, 30), 12)
        }
        val bannerParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        )
        bannerParams.setMargins(activity.dp(24), activity.dp(20), activity.dp(24), 0)
        contentColumn.addView(banner, bannerParams)

        val lyricArea = FrameLayout(activity)
        lyricsView.onSeek = null
        lyricArea.addView(lyricsView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        waitingText = textView(
            activity,
            "正在等待播放器送出第一句歌词…\n\n" +
                "一直不动就去「设置 → 歌词写在」换一行试试。三种情况：\n" +
                "· 歌手行 / 专辑行 —— 大部分播放器；\n" +
                "· 歌曲名行 —— 网易云 / QQ 音乐的「车载歌词」把歌词写进了歌曲名那一行，\n" +
                "  这种必须选「歌曲名行」（默认的「自动判断」也能认出来）。",
            18,
            Palette.TEXT_SECONDARY,
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.6f)
            setPaddingDp(32, 32, 32, 32)
            visibility = View.GONE
        }
        lyricArea.addView(
            waitingText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        contentColumn.addView(
            lyricArea,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )
        rightPanel.addView(
            contentColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        idleScroll = ScrollView(activity).apply { isFillViewport = true }
        idleColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingDp(36, 36, 36, 36)
        }
        idleScroll.addView(
            idleColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        rightPanel.addView(idleScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ------------------------------------------------------
        // 组合：中间是可拖动的分栏条，比例存设置里
        // ------------------------------------------------------
        splitFraction = AppSettings.sanitizeSplitRatio(app.settings.splitRatio)
        splitHandle = SplitHandle(activity, vertical = compact).apply {
            onFraction = { fraction -> applySplit(fraction) }
            onRelease = { fraction ->
                applySplit(fraction)
                app.setSplitRatio(splitFraction)
            }
            onReset = {
                applySplit(AppSettings.DEFAULT_SPLIT_RATIO)
                app.setSplitRatio(splitFraction)
                toast(activity, "分栏已恢复默认")
            }
        }
        if (compact) {
            addView(
                leftPanel,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, splitFraction),
            )
            addView(
                splitHandle,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(HANDLE_DP)),
            )
            addView(
                rightPanel,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f - splitFraction),
            )
        } else {
            addView(
                leftPanel,
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, splitFraction),
            )
            addView(
                splitHandle,
                LinearLayout.LayoutParams(activity.dp(HANDLE_DP), LayoutParams.MATCH_PARENT),
            )
            addView(
                rightPanel,
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - splitFraction),
            )
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = lastState.media.durationMs
                if (duration > 0) {
                    positionText.text = formatMs(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
                val duration = lastState.media.durationMs
                if (duration > 0 && lastState.media.hasTrack) {
                    app.repository.seek(duration * (bar?.progress ?: 0) / 1000)
                }
            }
        })

        applyInsets()
        applyLyricDisplay()
        render(lastState)
        refreshNotificationAccess()
    }

    /** 设置里的歌词样式变了：立刻套到正在显示的歌词上，不重建界面。 */
    fun applyLyricDisplay() {
        lyricsView.applyStyle(lyricStyleOf(app.settings.lyric))
    }

    private fun mediaButton(iconRes: Int, sizeDp: Int, onClick: () -> Unit): ImageButton =
        ImageButton(activity).apply {
            setImageResource(iconRes)
            background = null
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Palette.TEXT_PRIMARY)
            setPaddingDp(sizeDp / 5, sizeDp / 5, sizeDp / 5, sizeDp / 5)
            setOnClickListener { onClick() }
        }

    private fun applyInsets() {
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        requestApplyInsets()
    }

    fun render(state: LyricSessionState) {
        lastState = state
        val media = state.media

        val title = media.title.ifBlank { "未在播放" }
        if (titleText.text.toString() != title) titleText.text = title
        val artist = "歌手：${media.artist.ifBlank { "—" }}"
        if (artistText.text.toString() != artist) artistText.text = artist
        val album = "专辑：${media.album.ifBlank { "—" }}"
        if (albumText.text.toString() != album) albumText.text = album

        if (media.appLabel.isNotEmpty()) {
            val source = "来自 ${media.appLabel}"
            if (sourceChip.text.toString() != source) sourceChip.text = source
            sourceChip.visibility = View.VISIBLE
        } else {
            sourceChip.visibility = View.GONE
        }

        updateCover(media.artUri, media.artBytes)

        val total = media.durationMs
        val position = (state.derivedPositionMs ?: media.positionMs).coerceAtLeast(0)
        val shown = if (total > 0 && position > total) total else position
        if (!dragging) {
            seekBar.progress = if (total <= 0) 0 else ((shown * 1000) / total).toInt().coerceIn(0, 1000)
            positionText.text = formatMs(shown)
        }
        durationText.text = if (total <= 0) "--:--" else formatMs(total)
        seekBar.isEnabled = media.hasTrack && total > 0

        playButton.setImageResource(
            if (state.playingNow) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play,
        )
        prevButton.isEnabled = media.hasTrack
        playButton.isEnabled = media.hasTrack
        nextButton.isEnabled = media.hasTrack
        prevButton.alpha = if (media.hasTrack) 1f else 0.35f
        playButton.alpha = if (media.hasTrack) 1f else 0.35f
        nextButton.alpha = if (media.hasTrack) 1f else 0.35f

        val status = when (state.mode) {
            LyricMode.IDLE -> "歌词库：${state.storedCount} 首"
            LyricMode.CAPTURING -> "采集歌词中 · 歌词库：${state.storedCount} 首"
            LyricMode.REPLAY ->
                if (state.collecting) {
                    "回放中（还在补没采到的部分） · 歌词库：${state.storedCount} 首"
                } else {
                    "歌词回放中 · 歌词库：${state.storedCount} 首"
                }
        }
        if (statusText.text.toString() != status) statusText.text = status

        if (state.mode == LyricMode.IDLE) {
            contentColumn.visibility = View.GONE
            idleScroll.visibility = View.VISIBLE
            if (lastAccess == null) refreshNotificationAccess()
        } else {
            idleScroll.visibility = View.GONE
            contentColumn.visibility = View.VISIBLE
            applyBanner(state)
            lyricsView.onSeek = if (state.mode == LyricMode.REPLAY) { line ->
                app.repository.seek(line.timeMs)
            } else {
                null
            }
            lyricsView.setLines(state.lines, state.currentIndex)
            waitingText.visibility =
                if (state.mode == LyricMode.CAPTURING && state.lines.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun applyBanner(state: LyricSessionState) {
        val text = when (state.mode) {
            LyricMode.CAPTURING -> when {
                state.positionStale ->
                    "⚠ 播放器不再上报播放位置，这一遍先不采集\n" +
                        "（每句都会记成同一个时间点，存下来没用）\n" +
                        "已经存过的歌照常滚动显示；换首歌或重连蓝牙后会自动恢复。"
                !state.fromStart ->
                    "✂ 开头没采到（打开 App 时这首歌已经唱了一半）·已 ${state.lines.size} 句\n" +
                        "把这句歌从头再放一遍就会自动补齐，不会重复。"
                state.complete ->
                    "✓ 这首歌的歌词已经采集完整，下次播放直接显示"
                else ->
                    "● 第一遍听：正在一边播一边采集歌词（已 ${state.lines.size} 句）"
            }
            LyricMode.REPLAY -> if (state.collecting) {
                if (!state.fromStart) {
                    "＋ 开头那段还没采到 · 已 ${state.lines.size} 句\n" +
                        "缺的部分会边放边补；从头再放一遍就能把开头补上，已有的句子不会被改乱。"
                } else {
                    "＋ 这首歌还没采完（已 ${state.lines.size} 句）· 回放中，缺的部分会自动补上"
                }
            } else {
                ""
            }
            LyricMode.IDLE -> ""
        }
        if (text.isEmpty()) {
            banner.visibility = View.GONE
        } else {
            banner.visibility = View.VISIBLE
            if (banner.text.toString() != text) banner.text = text
        }
    }

    private fun updateCover(uri: String, bytes: ByteArray?) {
        if (bytes == null) {
            cover.visibility = View.GONE
            coverPlaceholder.visibility = View.VISIBLE
            lastArtUri = ""
            lastArtBytes = null
            return
        }
        if (uri == lastArtUri && bytes === lastArtBytes) return
        lastArtUri = uri
        lastArtBytes = bytes
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            cover.visibility = View.GONE
            coverPlaceholder.visibility = View.VISIBLE
        } else {
            cover.setImageBitmap(bitmap)
            cover.visibility = View.VISIBLE
            coverPlaceholder.visibility = View.GONE
        }
    }

    fun refreshNotificationAccess() {
        val granted = try { app.repository.hasNotificationAccess() } catch (_: Exception) { false }
        val browserAvailable = app.repository.hasAlternativeSource()
        if (lastAccess == granted && lastBrowserAvailable == browserAvailable &&
            idleColumn.childCount > 0
        ) {
            return
        }
        lastAccess = granted
        lastBrowserAvailable = browserAvailable
        rebuildIdle(granted, browserAvailable)
    }

    private fun rebuildIdle(granted: Boolean, browserAvailable: Boolean) {
        idleColumn.removeAllViews()
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(textView(activity, "♫", 26, Palette.ACCENT))
        titleRow.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        titleRow.addView(
            textView(
                activity,
                if (granted || browserAvailable) "等待音乐播放" else "先开一个系统权限",
                24,
                Palette.TEXT_PRIMARY,
                bold = true,
            ),
        )
        idleColumn.addView(titleRow, matchWidthWrap())
        idleColumn.addView(space(activity, 18))

        if (!granted && !browserAvailable) {
            idleColumn.addView(
                bodyText(
                    "读取不了「正在播放哪首歌」的话，歌词就无从采起。\n" +
                        "很多车机（比如部分比亚迪）可以在设置里授予「通知使用权」，\n" +
                        "这是 Android 允许普通 App 读取别的播放器（网易云 / QQ 音乐 /\n" +
                        "车机自带蓝牙播放器）媒体信息的入口；如果设置里没有这个页面，\n" +
                        "请按下面的 adb 命令开启。",
                ),
                matchWidthWrap(),
            )
            idleColumn.addView(space(activity, 22))
            idleColumn.addView(
                filledButton(activity, "去开启通知使用权") {
                    app.repository.openNotificationAccessSettings()
                },
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
            idleColumn.addView(space(activity, 14))
            idleColumn.addView(
                bodyText(
                    "路径：设置 → 应用 → 特殊应用权限 → 通知使用权 → 勾选「LyricCar 歌词」",
                ),
                matchWidthWrap(),
            )
            idleColumn.addView(space(activity, 18))
            idleColumn.addView(adbHint(), matchWidthWrap())
        } else {
            val browserNote = if (browserAvailable && !granted) {
                "本车机通过蓝牙媒体浏览器直接读取播放信息，不需要通知使用权。\n\n"
            } else {
                ""
            }
            val readyText = if (browserAvailable && !granted) {
                "蓝牙媒体浏览器已就绪。"
            } else {
                "权限已就绪。"
            }
            idleColumn.addView(
                bodyText(
                    browserNote + readyText +
                    "现在用手机 / 车机上的播放器放一首歌，并确认：\n\n" +
                        "1. 声音是「车机自带蓝牙播放器」在放（本 App 不去接管音频）；\n" +
                        "2. 播放器已经把歌词那一行写进了媒体信息（网易云 / QQ 音乐在\n" +
                        "   设置里叫「车载歌词 / 蓝牙歌词」，要先打开）；\n" +
                        "3. 第一遍听会把歌词一句句攒下来，第二遍开始就直接显示完整歌词。",
                ),
                matchWidthWrap(),
            )
            idleColumn.addView(space(activity, 22))
            idleColumn.addView(
                outlinedButton(activity, "重新检测") {
                    app.repository.checkAccessAndRearm()
                    app.runSelfCheckAsync()
                    postDelayed({ refreshNotificationAccess() }, 400)
                    toast(activity, "已重新检测")
                },
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun adbHint(): View {
        val listener = app.repository.listenerComponentName().ifBlank { "包名/服务类名" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Palette.SURFACE, 12)
            setPaddingDp(18, 18, 18, 18)
        }
        box.addView(
            bodyText(
                "如果点按钮弹的是「不支持该操作」，说明这台车机的设置里没有这个页面。\n" +
                    "用电脑连上车机（adb devices 能看到设备），跑下面对应那条命令即可 ——\n" +
                    "只需一次，之后一直有效，重启也不会掉。",
            ),
            matchWidthWrap(),
        )
        box.addView(space(activity, 14))
        box.addView(
            textView(
                activity,
                "Android 7.0 及以上",
                14,
                Palette.TEXT_SECONDARY,
                bold = true,
            ),
        )
        box.addView(space(activity, 4))
        box.addView(
            commandText("adb shell cmd notification allow_listener $listener"),
            matchWidthWrap(),
        )
        box.addView(space(activity, 12))
        box.addView(
            textView(
                activity,
                "Android 6.0（没有 cmd notification，改用这个）",
                14,
                Palette.TEXT_SECONDARY,
                bold = true,
            ),
        )
        box.addView(space(activity, 4))
        box.addView(
            commandText(
                "adb shell settings put secure enabled_notification_listeners $listener",
            ),
            matchWidthWrap(),
        )
        box.addView(space(activity, 12))
        box.addView(
            bodyText(
                "怎么确认成功：跑完在电脑上看一眼\n" +
                    "adb shell settings get secure enabled_notification_listeners，\n" +
                    "结果里有 $listener 就对了；回到 App 点「重新检测」。",
            ),
            matchWidthWrap(),
        )
        return box
    }

    private fun bodyText(value: String): TextView =
        textView(activity, value, 18, Palette.TEXT_SECONDARY).apply {
            setLineSpacing(0f, 1.6f)
        }

    private fun commandText(value: String): TextView =
        textView(activity, value, 15, Palette.ACCENT).apply {
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.4f)
            setTextIsSelectable(true)
        }

    private fun showAboutSheet() {
        AboutSheet(activity).show()
    }

    private fun showLibrarySheet() {
        LibrarySheet(activity, app, downloads).show()
    }

    private fun showSettingsSheet() {
        SettingsSheet(activity, app, downloads).show()
    }

    /**
     * 应用左右 / 上下分栏比例。比例是「左（上）栏占可用空间的比例」，
     * 太窄时按最小宽度兜底，避免把某一边挤没。
     */
    private fun applySplit(fraction: Float) {
        splitFraction = clampFraction(fraction)
        if (compact) {
            leftPanel.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                splitFraction,
            )
            rightPanel.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f - splitFraction,
            )
        } else {
            leftPanel.layoutParams = LinearLayout.LayoutParams(
                0,
                LayoutParams.MATCH_PARENT,
                splitFraction,
            )
            rightPanel.layoutParams = LinearLayout.LayoutParams(
                0,
                LayoutParams.MATCH_PARENT,
                1f - splitFraction,
            )
        }
    }

    private fun clampFraction(fraction: Float): Float {
        val raw = fraction.coerceIn(AppSettings.MIN_SPLIT_RATIO, AppSettings.MAX_SPLIT_RATIO)
        if (compact) return raw.coerceIn(COMPACT_MIN_FRACTION, COMPACT_MAX_FRACTION)
        val handle = splitHandle.width
        val usable = width - paddingLeft - paddingRight - handle
        if (usable <= 0 || handle <= 0) return raw
        val minLeft = activity.dp(MIN_LEFT_DP) / usable.toFloat()
        val minRight = activity.dp(MIN_RIGHT_DP) / usable.toFloat()
        // 车机屏幕太窄、两个最小宽度塞不下：退化成固定区间。
        if (minLeft + minRight >= 0.92f) return raw.coerceIn(0.25f, 0.75f)
        return raw.coerceIn(minLeft, 1f - minRight)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private companion object {
        const val HANDLE_DP = 18
        const val MIN_LEFT_DP = 280
        const val MIN_RIGHT_DP = 300
        const val COMPACT_MIN_FRACTION = 0.35f
        const val COMPACT_MAX_FRACTION = 0.65f
    }
}
