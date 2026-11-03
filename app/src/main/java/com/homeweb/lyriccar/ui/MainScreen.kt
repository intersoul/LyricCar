package com.homeweb.lyriccar.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
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
import com.homeweb.lyriccar.model.BackgroundMode
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.LyricMode
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.PanelTone
import com.homeweb.lyriccar.model.SourceChannel
import com.homeweb.lyriccar.model.ThemeMode
import com.homeweb.lyriccar.model.artistFromTitleArtistLine
import com.homeweb.lyriccar.model.normalizeTitle
import com.homeweb.lyriccar.model.songNameFromTitleArtistLine
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
    private var lastChannel: SourceChannel = app.settings.channel
    private var dragging = false
    private var lastArtUri = ""
    private var lastArtBytes: ByteArray? = null

    /**
     * 窗口是竖着的（屏幕竖屏，或者车机「平行世界」里 App 被挤成竖条）。
     *
     * 这种情况按网易云歌词那种排法：上边整块歌词，下边一条控制区，
     * 封面不再显示 —— 竖着的地方本来就窄，封面一放标题和歌名就没地方了。
     */
    private val portrait =
        resources.configuration.screenHeightDp > resources.configuration.screenWidthDp
    private val coverFrame: FrameLayout
    private val leftPanel: LinearLayout
    private val rightPanel: FrameLayout
    private val splitHandle: SplitHandle
    private var splitFraction = AppSettings.DEFAULT_SPLIT_RATIO
    private var backgroundBitmap: Bitmap? = null
    private var backgroundStamp = -1L

    init {
        setBackgroundColor(Palette.BACKGROUND)
        orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

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
            if (portrait) {
                setPaddingDp(20, 18, 20, 16)
            } else {
                setPaddingDp(28, 28, 28, 28)
            }
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
        coverFrame = FrameLayout(activity).apply {
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
        if (portrait) coverFrame.visibility = View.GONE

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleText = textView(
            activity,
            "未在播放",
            if (portrait) 21 else 24,
            Palette.TEXT_PRIMARY,
            bold = true,
        ).apply {
            // 标题栏显示的是歌名（不是歌词），所以正常只有一两行；长歌名给到 2/4 行。
            maxLines = if (portrait) 2 else 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
            minHeight = activity.dp(if (portrait) 26 else 60)
        }
        artistText = textView(activity, "歌手：—", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        albumText = textView(activity, "专辑：—", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (portrait) visibility = View.GONE
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
        // 进度条以下的控件钉在底部：左边标题一句长一句短时，下面整排控件不再跟着上下跳。
        // ScrollView 开了 fillViewport，内容没超过一屏时这个占位会把下面的内容顶到底。
        // 竖屏时整块都贴底（歌名 + 进度 + 控制键），空出来的地方留在歌词下边。
        if (portrait) {
            leftColumn.addView(
                View(activity),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        leftColumn.addView(topRow, matchWidthWrap())

        leftColumn.addView(space(activity, if (portrait) 12 else 26))
        if (!portrait) {
            leftColumn.addView(
                View(activity),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

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

        leftColumn.addView(space(activity, if (portrait) 10 else 18))

        statusText = textView(activity, "歌词库：0 首", 15, Palette.TEXT_SECONDARY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 竖屏下控制区就一条，先把「歌词库：N 首」让出来。
            if (portrait) visibility = View.GONE
        }
        leftColumn.addView(statusText, matchWidthWrap())

        leftColumn.addView(space(activity, if (portrait) 10 else 18))

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
        //   横屏：左控制栏 │ 竖条 │ 歌词
        //   竖屏：上歌词   │ 横条 │ 下控制栏
        // splitFraction 始终表示「控制区」那一栏占的比例（横屏是左边，竖屏是下边）。
        // ------------------------------------------------------
        splitFraction = AppSettings.sanitizeSplitRatio(app.settings.splitRatio)
        splitHandle = SplitHandle(activity, vertical = portrait).apply {
            onFraction = { fraction -> applySplit(controlShareOf(fraction)) }
            onRelease = { fraction ->
                applySplit(controlShareOf(fraction))
                app.setSplitRatio(splitFraction)
            }
            onReset = {
                applySplit(AppSettings.DEFAULT_SPLIT_RATIO)
                app.setSplitRatio(splitFraction)
                toast(activity, "分栏已恢复默认")
            }
        }
        if (portrait) {
            addView(
                rightPanel,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f - splitFraction),
            )
            addView(
                splitHandle,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, activity.dp(HANDLE_DP)),
            )
            addView(
                leftPanel,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, splitFraction),
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
        // 从横屏切过来的比例可能不适合竖屏（竖屏只有 20%~60% 可用），先按新方向收一次。
        applySplit(splitFraction)

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
        applyBackground()
        render(lastState)
        refreshNotificationAccess()
    }

    /** 设置里的歌词样式变了：立刻套到正在显示的歌词上，不重建界面。 */
    fun applyLyricDisplay() {
        lyricsView.applyStyle(lyricStyleOf(app.settings.lyric))
    }

    /**
     * 主界面背景：默认 / 纯色 / 自定义图片。
     *
     * 图片会带一层主题遮罩，保证歌词看得清；左栏（控制区）铺不铺底由
     * 「背景 → 左栏底色」决定 —— 铺半透明看得清，透明就是整块图露出来。
     */
    fun applyBackground() {
        when (app.settings.background.mode) {
            BackgroundMode.DEFAULT -> {
                dropBackgroundBitmap()
                setBackgroundColor(Palette.BACKGROUND)
                leftPanel.background = leftPanelBackground()
                rightPanel.setBackgroundColor(Palette.BACKGROUND)
            }

            BackgroundMode.COLOR -> {
                dropBackgroundBitmap()
                setBackgroundColor(
                    app.settings.background.color.colorFor(Palette.mode == ThemeMode.LIGHT),
                )
                leftPanel.background = null
                rightPanel.setBackgroundColor(Color.TRANSPARENT)
            }

            BackgroundMode.IMAGE -> {
                val file = app.backgroundImageFile()
                val stamp = file.lastModified()
                if (backgroundBitmap == null || stamp != backgroundStamp) {
                    dropBackgroundBitmap()
                    backgroundBitmap = BackgroundImages.loadSaved(activity)
                    backgroundStamp = stamp
                }
                val bitmap = backgroundBitmap
                if (bitmap == null || bitmap.isRecycled) {
                    // 图片丢了就退回默认背景，别留一块黑
                    setBackgroundColor(Palette.BACKGROUND)
                    leftPanel.background = leftPanelBackground()
                    rightPanel.setBackgroundColor(Palette.BACKGROUND)
                } else {
                    val image = BitmapDrawable(resources, bitmap).apply {
                        gravity = Gravity.FILL
                        isFilterBitmap = true
                    }
                    val scrim = ColorDrawable(
                        withAlpha(
                            Palette.BACKGROUND,
                            if (Palette.mode == ThemeMode.LIGHT) 180 else 150,
                        ),
                    )
                    setBackground(LayerDrawable(arrayOf(image, scrim)))
                    // 左栏铺不铺底：透明时整块图直接露出来，只剩中间那条分栏条。
                    leftPanel.background =
                        if (app.settings.background.panel == PanelTone.TRANSPARENT) {
                            null
                        } else {
                            leftPanelBackground(alpha = 200)
                        }
                    rightPanel.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }
        applyCoverFrameBackground()
    }

    /** 左栏整块透明时，封面的占位方块也别留一块底色。 */
    private fun applyCoverFrameBackground() {
        val background = app.settings.background
        val clear = background.mode == BackgroundMode.IMAGE &&
            background.panel == PanelTone.TRANSPARENT
        coverFrame.background = if (clear) null else roundedBackground(Palette.SURFACE, 16)
    }

    private fun dropBackgroundBitmap() {
        backgroundBitmap?.let { if (!it.isRecycled) it.recycle() }
        backgroundBitmap = null
        backgroundStamp = -1L
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

        val title = state.songTitle.trim().ifEmpty {
            // 认不出歌名（半截插进来、播放器又把歌词写进了歌曲名行）就别拿歌词顶上，
            // 否则标题栏会跟着歌词一句句跳。
            if (state.mode == LyricMode.IDLE) "未在播放" else "未知歌名"
        }
        if (titleText.text.toString() != title) titleText.text = title
        val artist = "歌手：${artistLabel(state)}"
        if (artistText.text.toString() != artist) artistText.text = artist
        val album = "专辑：${media.album.ifBlank { "—" }}"
        if (albumText.text.toString() != album) albumText.text = album

        if (!portrait && media.appLabel.isNotEmpty()) {
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

        // 首数放最前：左栏窄的时候后面的字会被省略掉，首数要保证看得见。
        val stored = "歌词库：${state.storedCount} 首"
        val status = when (state.mode) {
            LyricMode.IDLE -> stored
            LyricMode.CAPTURING -> "$stored · 正在采集"
            LyricMode.REPLAY ->
                if (state.collecting) {
                    "$stored · 回放中（还在补没采到的部分）"
                } else {
                    "$stored · 回放中"
                }
        } + channelNote()
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

    /** 「歌手：」那一行。网易云这类把「歌名 - 歌手」写在歌手行时，只留歌手名。 */
    private fun artistLabel(state: LyricSessionState): String {
        val raw = state.media.artist.trim()
        if (raw.isEmpty()) return "—"
        val name = state.songTitle.trim()
        if (name.isEmpty()) return raw
        val parsedName = songNameFromTitleArtistLine(raw) ?: return raw
        if (normalizeTitle(parsedName) != normalizeTitle(name)) return raw
        return artistFromTitleArtistLine(raw) ?: raw
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
            lastChannel == app.settings.channel && idleColumn.childCount > 0
        ) {
            return
        }
        lastAccess = granted
        lastBrowserAvailable = browserAvailable
        lastChannel = app.settings.channel
        rebuildIdle(granted, browserAvailable)
    }

    /** 设置里换了通道：状态行和空界面都要跟着换说明。 */
    fun onChannelChanged() {
        if (lastChannel == app.settings.channel) return
        render(app.currentState())
        refreshNotificationAccess()
    }

    /** 状态行末尾的通道提示：只有手动指定通道时才占位置。 */
    private fun channelNote(): String = when (app.settings.channel) {
        SourceChannel.AUTO -> ""
        SourceChannel.BLUETOOTH -> when {
            !app.repository.hasAlternativeSource() -> " · 蓝牙通道不可用"
            app.repository.bluetoothConnected() -> " · 蓝牙通道"
            else -> " · 蓝牙通道等待连接"
        }
        SourceChannel.SESSION -> " · 系统会话"
    }

    private fun idleTitle(text: String): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(textView(activity, "♫", 26, Palette.ACCENT))
        row.addView(space(activity, 0).apply {
            layoutParams = LinearLayout.LayoutParams(activity.dp(10), 1)
        })
        row.addView(textView(activity, text, 24, Palette.TEXT_PRIMARY, bold = true))
        return row
    }

    private fun rebuildIdle(granted: Boolean, browserAvailable: Boolean) {
        idleColumn.removeAllViews()
        val channel = app.settings.channel
        if (channel == SourceChannel.BLUETOOTH && !browserAvailable) {
            idleColumn.addView(idleTitle("蓝牙通道不可用"), matchWidthWrap())
            idleColumn.addView(space(activity, 18))
            idleColumn.addView(
                bodyText(
                    "通道被手动设成了「蓝牙」，但这台车机的系统里找不到蓝牙媒体浏览器服务，\n" +
                        "所以现在读不到任何播放信息。\n\n" +
                        "「蓝牙」是给「用媒体中心放蓝牙音乐、系统会话里没有歌名」的车机准备的；\n" +
                        "如果你的车机不在这个情况里，切回「自动」就好。",
                ),
                matchWidthWrap(),
            )
            idleColumn.addView(space(activity, 22))
            idleColumn.addView(
                outlinedButton(activity, "改回自动通道") {
                    app.setChannel(SourceChannel.AUTO)
                },
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
            idleColumn.addView(space(activity, 18))
            idleColumn.addView(bodyText(app.repository.channelStateText()), matchWidthWrap())
            return
        }
        val needAccess = !granted && (!browserAvailable || channel == SourceChannel.SESSION)
        idleColumn.addView(
            idleTitle(if (needAccess) "先开一个系统权限" else "等待音乐播放"),
            matchWidthWrap(),
        )
        idleColumn.addView(space(activity, 18))

        if (needAccess) {
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
            val readyText = when {
                channel == SourceChannel.BLUETOOTH ->
                    "通道：蓝牙（只看蓝牙通道里的播放信息）。"
                channel == SourceChannel.SESSION ->
                    "通道：系统会话（只读播放器自己报的媒体信息）。"
                browserAvailable && !granted -> "蓝牙媒体浏览器已就绪。"
                else -> "权限已就绪。"
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
     * 应用左右 / 上下分栏比例。[fraction] 是「控制区」那一栏的占比：
     * 横屏是左边那一栏，竖屏是下边那一条。
     */
    private fun applySplit(fraction: Float) {
        splitFraction = clampFraction(fraction)
        if (portrait) {
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

    /** 分栏条拖出来的比例是「上 / 左那一栏」的占比，竖屏时上边是歌词，要反过来。 */
    private fun controlShareOf(handleFraction: Float): Float =
        if (portrait) 1f - handleFraction else handleFraction

    /** 竖屏时控制区（下边）的可用高度，单位 dp。还没布局时按整屏估。 */
    private fun usableHeightDp(): Float {
        val usablePx = height - paddingTop - paddingBottom - splitHandle.height
        if (usablePx <= 0) return resources.configuration.screenHeightDp.toFloat()
        return usablePx / resources.displayMetrics.density
    }

    private fun clampFraction(fraction: Float): Float {
        val raw = fraction.coerceIn(AppSettings.MIN_SPLIT_RATIO, AppSettings.MAX_SPLIT_RATIO)
        if (portrait) {
            val usable = usableHeightDp()
            if (usable <= 0f) return raw
            val minControls = MIN_CONTROLS_DP / usable
            val maxControls = 1f - MIN_LYRICS_DP / usable
            // 窗口太矮、两个下限加起来超过整屏：退化成固定区间。
            if (minControls >= maxControls) return raw.coerceIn(0.32f, 0.62f)
            return raw.coerceIn(minControls.coerceAtLeast(0.16f), maxControls.coerceAtMost(0.84f))
        }
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
        /** 竖屏时分栏条上下两边的最小高度：下边控制区、上边歌词区。 */
        const val MIN_CONTROLS_DP = 250
        const val MIN_LYRICS_DP = 190
    }
}
