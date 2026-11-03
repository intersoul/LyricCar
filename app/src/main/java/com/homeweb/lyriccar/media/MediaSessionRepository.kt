package com.homeweb.lyriccar.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.homeweb.lyriccar.MediaSessionListenerService
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.model.MediaInfo
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 系统媒体会话读取器。
 *
 * 数据流：
 *   MediaSessionManager.getActiveSessions(监听器组件)
 *     → 挑一个「正在播且有歌名」的会话当主角
 *     → MediaController.registerCallback 盯住元数据 / 播放状态
 *     → 250ms 一次外推播放位置，回调给采集引擎
 *
 * 反向操作（播放 / 暂停 / 上一首 / 下一首 / 拖动）都转手给 MediaController，
 * 本 App 自己不出声。
 */
class MediaSessionRepository(private val context: Context) {

    /** 每次状态变化回调一次；位置按 250ms 外推。 */
    @Volatile
    var listener: ((MediaInfo) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent =
        ComponentName(context, MediaSessionListenerService::class.java)

    private var controllers: List<MediaController> = emptyList()
    private var controller: MediaController? = null
    private var running = false
    private var lastSignature: String? = null
    private var lastLoggedHead: String? = null

    private var artUri = ""
    private var artBytes: ByteArray? = null
    private var artSentForUri: String? = null
    private val labelCache = mutableMapOf<String, String>()

    private var smoothedPosition: Long? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            controllers = list.orEmpty()
            selectController(chooseController())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit(false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit(false)
        override fun onSessionDestroyed() {
            refreshSessions()
            selectController(chooseController())
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            emit(false)
            handler.postDelayed(this, if (isPlaying(controller)) TICK_PLAYING_MS else TICK_IDLE_MS)
        }
    }

    // ----------------------------------------------------------
    // 生命周期
    // ----------------------------------------------------------
    fun start() {
        if (running) return
        running = true
        lastSignature = null
        enableSessionsWatch()
        emit(true)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_PLAYING_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(ticker)
        unregisterSessionsListener()
        controller?.unregisterCallback(controllerCallback)
        controller = null
        smoothedPosition = null
    }

    /** 回到前台时重新挂一遍：用户可能刚去系统设置里开了通知使用权。 */
    fun checkAccessAndRearm() {
        val granted = hasNotificationAccess()
        Diagnostics.log("通知使用权：$granted")
        if (granted && running) enableSessionsWatch()
    }

    // ----------------------------------------------------------
    // 权限 / 诊断 / 播放控制
    // ----------------------------------------------------------
    fun hasNotificationAccess(): Boolean {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        return raw.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == listenerComponent
        }
    }

    fun listenerComponentName(): String = listenerComponent.flattenToString()

    fun openNotificationAccessSettings() {
        val intents = listOf(
            Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.parse("package:${context.packageName}"),
            ),
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // 这台 ROM 可能没有这个页面，试下一个
            }
        }
    }

    fun play() {
        controller?.transportControls?.play()
    }

    fun pause() {
        controller?.transportControls?.pause()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    fun seek(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    // ----------------------------------------------------------
    // 会话发现
    // ----------------------------------------------------------
    private fun refreshSessions() {
        controllers = try {
            sessionManager.getActiveSessions(listenerComponent).orEmpty()
        } catch (error: SecurityException) {
            Log.i(TAG, "读不到媒体会话（缺通知使用权）：${error.message}")
            emptyList()
        } catch (error: Exception) {
            Log.w(TAG, "读媒体会话失败", error)
            emptyList()
        }
    }

    /**
     * 挑主角：正在播且有歌名 > 有歌名 > 保持上一次 > 正在播但没歌名。
     * 最后那条是给 BYD 的 com.byd.mediacenter 准备的 —— 它报 PLAYING 却没有
     * metadata，不能让它把真正放歌的蓝牙会话挤掉。
     */
    private fun chooseController(): MediaController? {
        controllers.firstOrNull { isPlaying(it) && hasTitle(it) }?.let { return it }
        controllers.firstOrNull { hasTitle(it) }?.let { return it }
        controller?.let { current ->
            if (controllers.any { it.sessionToken == current.sessionToken }) return current
        }
        return controllers.firstOrNull { isPlaying(it) } ?: controllers.firstOrNull()
    }

    private fun hasTitle(candidate: MediaController): Boolean =
        !candidate.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrEmpty()

    private fun selectController(next: MediaController?) {
        val current = controller
        if (current?.sessionToken == next?.sessionToken) {
            emit(true)
            return
        }
        current?.unregisterCallback(controllerCallback)
        controller = next

        artUri = ""
        artBytes = null
        artSentForUri = null
        smoothedPosition = null

        next?.registerCallback(controllerCallback, handler)
        Diagnostics.log("选中会话：${describe(next)}")
        emit(true)
    }

    private fun describe(target: MediaController?): String {
        if (target == null) return "（无）"
        val metadata = target.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        return "${labelFor(target.packageName)}[$title] 歌手=$artist 专辑=$album"
    }

    private fun enableSessionsWatch() {
        unregisterSessionsListener()
        refreshSessions()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                handler,
            )
        } catch (error: Exception) {
            Log.i(TAG, "注册全量会话监听失败，退化为本应用会话：${error.message}")
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, null)
            } catch (inner: Exception) {
                Log.w(TAG, "注册会话监听彻底失败", inner)
            }
        }
        Diagnostics.log("发现 ${controllers.size} 个媒体会话")
        selectController(chooseController())
    }

    private fun unregisterSessionsListener() {
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (error: Exception) {
            Log.w(TAG, "注销会话监听失败", error)
        }
    }

    // ----------------------------------------------------------
    // 状态推送
    // ----------------------------------------------------------
    private fun emit(force: Boolean) {
        val sink = listener ?: return
        val payload = buildPayload()
        val signature = signatureOf(payload)
        if (!force && signature == lastSignature) return
        lastSignature = signature

        val head = "${payload.title}|${payload.artist}|${payload.album}|${payload.playing}"
        if (head != lastLoggedHead) {
            lastLoggedHead = head
            Diagnostics.log(
                "推送：${payload.title} 歌手=${payload.artist} 专辑=${payload.album} " +
                    "播放中=${payload.playing} 位置=${payload.positionMs}ms",
            )
            if (DUMP_METADATA) dumpMetadata(controller?.metadata)
        }

        val smoothed = smooth(payload)
        sink(smoothed)
    }

    private fun signatureOf(info: MediaInfo): String = buildString {
        append(info.hasSession).append('\u0000')
        append(info.sessionCount).append('\u0000')
        append(info.packageName).append('\u0000')
        append(info.appLabel).append('\u0000')
        append(info.title).append('\u0000')
        append(info.artist).append('\u0000')
        append(info.album).append('\u0000')
        append(info.durationMs).append('\u0000')
        append(info.positionMs).append('\u0000')
        append(info.playing).append('\u0000')
        append(info.artUri)
    }

    private fun buildPayload(): MediaInfo {
        val current = controller
            ?: return MediaInfo(hasSession = false, sessionCount = controllers.size)

        val metadata = current.metadata
        val state = current.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val uri = artUriOf(metadata)
        if (uri != artUri) {
            artUri = uri
            artBytes = null
            artSentForUri = null
        }
        if (artBytes == null && artUri.isNotEmpty()) {
            artBytes = loadArt(metadata, artUri)
        }

        return MediaInfo(
            hasSession = true,
            sessionCount = controllers.size,
            packageName = current.packageName,
            appLabel = labelFor(current.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = artistOf(metadata),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = durationMs,
            positionMs = positionMsOf(state, durationMs),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            artUri = artUri,
            artBytes = artBytes,
        )
    }

    /**
     * 把播放位置这个会来回跳的时钟压平：小幅度回退（< 2s）视为协议栈抖动，
     * 报上一次的值；掉回 1 秒以内是「重新播放」，照实报出去。
     */
    private fun smooth(info: MediaInfo): MediaInfo {
        if (!info.hasTrack) {
            smoothedPosition = null
            return info
        }
        val previous = smoothedPosition
        val position = info.positionMs
        if (previous != null && isJitter(previous, position)) {
            return info.withPosition(previous)
        }
        smoothedPosition = position
        return info
    }

    private fun isJitter(previous: Long, position: Long): Boolean {
        if (position >= previous) return false
        if (position < 1000 && previous >= 1000) return false
        return previous - position < 2000
    }

    private fun artistOf(metadata: MediaMetadata?): String {
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (!artist.isNullOrEmpty()) return artist
        return metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
    }

    private fun dumpMetadata(metadata: MediaMetadata?) {
        if (metadata == null) {
            Diagnostics.log("  元数据：空")
            return
        }
        val parts = metadata.keySet().mapNotNull { key ->
            try {
                val short = key.removePrefix("android.media.metadata.")
                val value = when {
                    metadata.getText(key) != null -> metadata.getText(key).toString()
                    metadata.getBitmap(key) != null -> "<bitmap>"
                    else -> metadata.getLong(key).toString()
                }
                val trimmed = if (value.length > DUMP_VALUE_MAX) {
                    value.take(DUMP_VALUE_MAX) + "…"
                } else {
                    value
                }
                "$short=$trimmed"
            } catch (_: Exception) {
                null
            }
        }
        Diagnostics.log("  元数据：${parts.joinToString(" | ")}")
    }

    private fun artUriOf(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        return metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: ""
    }

    /** 位置不会自己走，按「最后位置 + 经过时间 × 倍速」外推。 */
    private fun positionMsOf(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val updatedAt = state.lastPositionUpdateTime
            if (updatedAt > 0) {
                val elapsed = SystemClock.elapsedRealtime() - updatedAt
                position += (elapsed * state.playbackSpeed).toLong()
            }
        }
        if (durationMs > 0) position = position.coerceIn(0, durationMs)
        return position.coerceAtLeast(0)
    }

    private fun isPlaying(candidate: MediaController?): Boolean =
        candidate?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun labelFor(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
        labelCache[packageName] = label
        return label
    }

    // ----------------------------------------------------------
    // 封面
    // ----------------------------------------------------------
    private fun loadArt(metadata: MediaMetadata?, uri: String): ByteArray? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: readBitmap(uri)
            ?: return null
        return try {
            val scaled = scaleDown(bitmap)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "封面编码失败", error)
            null
        }
    }

    private fun readBitmap(uri: String): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    } catch (error: Exception) {
        Log.i(TAG, "封面读取失败：$uri (${error.message})")
        null
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= ART_MAX_PX) return bitmap
        val ratio = ART_MAX_PX.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    // ----------------------------------------------------------
    // 远程诊断
    // ----------------------------------------------------------
    fun diagnoseText(): String {
        val out = StringBuilder()
        out.appendLine("【系统】Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        out.appendLine(
            "【机型】${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE}）",
        )
        val granted = try { hasNotificationAccess() } catch (_: Exception) { false }
        out.appendLine("【通知使用权】$granted")
        out.appendLine("【监听组件】${listenerComponent.flattenToString()}")
        out.appendLine("【当前选中的播放器】${controller?.packageName ?: "（无）"}")

        val sessions = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (error: Exception) {
            out.appendLine("【可读会话】读取失败：${error.message ?: error.javaClass.simpleName}")
            return out.toString()
        }
        out.appendLine("【可读会话】${sessions.size} 个")
        sessions.forEachIndexed { index, item ->
            out.appendLine("")
            out.appendLine("--- 会话 ${index + 1}：${item.packageName} ---")
            val state = item.playbackState
            if (state == null) {
                out.appendLine("  PlaybackState：无")
            } else {
                out.appendLine(
                    "  PlaybackState：state=${state.state} position=${state.position} " +
                        "speed=${state.playbackSpeed} actions=${state.actions}",
                )
            }
            val metadata = item.metadata
            if (metadata == null) {
                out.appendLine("  元数据：无")
                return@forEachIndexed
            }
            out.appendLine("  元数据 ${metadata.size()} 项：")
            for (key in metadata.keySet()) {
                out.appendLine("    $key = ${describeValue(metadata, key)}")
            }
        }
        return out.toString()
    }

    private fun describeValue(metadata: MediaMetadata, key: String): String {
        val text = runCatching { metadata.getText(key) }.getOrNull()
        if (text != null) return shorten(text.toString())
        val bitmap = runCatching { metadata.getBitmap(key) }.getOrNull()
        if (bitmap != null) return "Bitmap(${bitmap.width}x${bitmap.height})"
        val number = runCatching { metadata.getLong(key) }.getOrNull()
        if (number != null) return number.toString()
        if (runCatching { metadata.getRating(key) }.getOrNull() != null) return "Rating"
        return "（其它类型）"
    }

    private fun shorten(value: String): String {
        val flat = value.replace("\n", " ⏎ ")
        return if (flat.length <= 120) flat else flat.substring(0, 120) + "…"
    }

    companion object {
        private const val TAG = "MediaSessionBridge"
        private const val TICK_PLAYING_MS = 250L
        private const val TICK_IDLE_MS = 1000L
        private const val ART_MAX_PX = 512
        private const val DUMP_METADATA = true
        private const val DUMP_VALUE_MAX = 60
    }
}
