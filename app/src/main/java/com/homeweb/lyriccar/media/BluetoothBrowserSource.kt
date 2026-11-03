package com.homeweb.lyriccar.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.util.Log
import com.homeweb.lyriccar.data.Diagnostics
import com.homeweb.lyriccar.model.MediaInfo

/**
 * 蓝牙 A2DP Sink 自带的 MediaBrowserService 数据源。
 *
 * 车机蓝牙栈把「手机通过蓝牙播放的歌」发布成一个导出的 MediaBrowserService：
 *   com.android.bluetooth/.a2dpsink.mbs.A2dpMediaBrowserService（高通方案常见）
 *   com.android.bluetooth/.avrcpcontroller.BluetoothMediaBrowserService（AOSP）
 * 还有的 ROM 换了包名 / 类名。
 *
 * 普通第三方 App 可以连接它、拿到 MediaSession.Token，再创建 MediaController，
 * 从而读取 TITLE / ARTIST / ALBUM / DURATION / 播放位置——**不需要通知使用权**。
 *
 * 这也是「媒体中心放蓝牙音乐、系统会话里没有歌名」时的备选通道：车机自己的
 * 媒体中心只报播放状态，歌名在蓝牙栈那边。所以这里不再写死一个组件名，
 * 而是启动时把可能的蓝牙媒体浏览器服务都找出来，逐个试。
 *
 * 数据源由 MediaSourceCoordinator 按用户选的通道调度，引擎 / 界面不关心来源。
 */
class BluetoothBrowserSource(private val context: Context) {

    @Volatile
    var listener: ((MediaInfo) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val labelCache = mutableMapOf<String, String>()

    private var started = false
    private var connecting = false
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    private var retryAttempt = 0
    /** 曾经成功绑定过服务（哪怕没拿到会话）。服务其实存在，只是暂时连不上/没有歌。 */
    private var checkBound = false
    /** 这一圈候选服务都试过了，都没连上。 */
    private var bindFailed = false
    private var lastFailure: String? = null
    private var lastSignature: String? = null
    private var lastInfo = MediaInfo.NONE
    private var lastLoggedHead: String? = null
    private var smoothedPosition: Long? = null

    private var localBaseElapsed = 0L

    /** 候选服务：启动时找一次，之后缓存。 */
    private val candidates: List<ComponentName> by lazy { discoverCandidates() }

    private var candidateIndex = 0
    private var failedThisRound = 0
    private var activeCandidate: ComponentName? = null

    private val retryRunnable = Runnable {
        if (started) connect()
    }

    private val connectRunnable = Runnable {
        if (started) connect()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (browser == null || controller == null) return
            emit(false)
            handler.postDelayed(this, if (isPlaying()) TICK_PLAYING_MS else TICK_IDLE_MS)
        }
    }

    private val connectionCallback = object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            connecting = false
            handler.removeCallbacks(connectTimeout)
            retryAttempt = 0
            failedThisRound = 0
            checkBound = true
            bindFailed = false
            lastFailure = null
            val token = try {
                browser?.sessionToken
            } catch (error: Exception) {
                Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 取 sessionToken 失败：$error")
                null
            }
            if (token == null) {
                Diagnostics.log(
                    "[MediaSource] 蓝牙 ${activeCandidate?.flattenToShortString()} 已连上，但暂时没有播放会话",
                )
                teardownController()
                emitIdle()
                scheduleRetry(null)
                return
            }
            val next = MediaController(context.applicationContext, token)
            controller = next
            next.registerCallback(controllerCallback, handler)
            updateLocalBase(next.playbackState)
            smoothedPosition = null
            lastSignature = null
            Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 已连接：${next.packageName}")
            startTicker()
            emit(true)
        }

        override fun onConnectionSuspended() {
            connecting = false
            handler.removeCallbacks(connectTimeout)
            Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 连接挂起，准备重连")
            teardownController()
            emitIdle()
            scheduleRetry(null)
        }

        override fun onConnectionFailed() {
            switchCandidateOrRetry("onConnectionFailed")
        }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            emit(false)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateLocalBase(state)
            emit(false)
        }

        override fun onSessionDestroyed() {
            Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 会话结束")
            teardownController()
            emitIdle()
            scheduleRetry(null)
        }
    }

    // ----------------------------------------------------------
    // 服务发现
    // ----------------------------------------------------------

    /**
     * 找出这台车机上「可能是蓝牙媒体浏览器」的服务：
     *   1. 两个已知组件名（高通 a2dpsink / AOSP avrcpcontroller）；
     *   2. 蓝牙相关包里名字带 MediaBrowser 的服务（ROM 改类名也能找到）；
     *   3. 声明了 MediaBrowserService intent 的蓝牙相关服务。
     * 只保留导出的（导出才能被第三方 App 绑定）。
     */
    private fun discoverCandidates(): List<ComponentName> {
        val found = LinkedHashSet<ComponentName>()
        found.addAll(KNOWN_COMPONENTS)
        for (pkg in BLUETOOTH_PACKAGES) {
            found.addAll(servicesOf(pkg))
        }
        found.addAll(intentServices())
        return found.filter { isExported(it) }
    }

    private fun servicesOf(packageName: String): List<ComponentName> {
        val info = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
        } catch (_: Exception) {
            return emptyList()
        }
        val services = info.services ?: return emptyList()
        return services
            .filter { it.name.contains("mediabrowser", ignoreCase = true) }
            .map { ComponentName(packageName, it.name) }
    }

    private fun intentServices(): List<ComponentName> {
        val resolved = try {
            context.packageManager.queryIntentServices(
                Intent(MediaBrowserService.SERVICE_INTERFACE),
                0,
            )
        } catch (_: Exception) {
            return emptyList()
        }
        return resolved.mapNotNull { it.serviceInfo }
            .filter { info ->
                val pkg = info.packageName.lowercase()
                val name = info.name.lowercase()
                pkg.contains("bluetooth") || pkg.contains("byd") ||
                    name.contains("a2dp") || name.contains("avrcp") ||
                    name.contains("bluetooth")
            }
            .map { ComponentName(it.packageName, it.name) }
    }

    private fun isExported(component: ComponentName): Boolean = try {
        context.packageManager.getServiceInfo(component, 0).exported
    } catch (_: Exception) {
        false
    }

    /** 服务是否存在且导出。只读查询，不需要通知使用权。 */
    fun isSupported(): Boolean = candidates.isNotEmpty()

    fun hasSession(): Boolean = controller != null

    /**
     * 这条数据源到底能不能用：系统里有可绑定的蓝牙媒体浏览器服务，
     * 或者绑定成功后只是暂时没歌。只有「一个候选都找不到」才算不可用。
     */
    fun isUsable(): Boolean {
        if (candidates.isEmpty()) return false
        if (controller != null || connecting) return true
        if (checkBound) return true
        return !bindFailed
    }

    fun failureReason(): String? = when {
        candidates.isEmpty() -> "系统没有蓝牙媒体浏览服务"
        controller != null || connecting || checkBound -> null
        bindFailed -> lastFailure ?: "连接失败"
        else -> null
    }

    fun latestInfo(): MediaInfo = lastInfo

    fun start() {
        if (started) return
        started = true
        if (candidates.isEmpty()) {
            Diagnostics.log("[MediaSource] 未找到蓝牙 MediaBrowserService（候选 0 个）")
            emitIdle()
            return
        }
        Diagnostics.log(
            "[MediaSource] 蓝牙候选服务 ${candidates.size} 个：" +
                candidates.joinToString(" | ") { it.flattenToShortString() },
        )
        candidateIndex = 0
        failedThisRound = 0
        connect()
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        connecting = false
        teardownController()
        teardownBrowser()
        candidateIndex = 0
        failedThisRound = 0
        activeCandidate = null
        lastSignature = null
        lastInfo = MediaInfo.NONE
        smoothedPosition = null
    }

    /** 回到前台 / 用户点「重新检测」/ 刚把通道切到蓝牙时重新连一次。 */
    fun checkAndReconnect() {
        if (!started) return
        if (controller != null) {
            emit(true)
            return
        }
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(connectRunnable)
        retryAttempt = 0
        candidateIndex = 0
        failedThisRound = 0
        checkBound = false
        bindFailed = false
        lastFailure = null
        connect()
    }

    private fun connect() {
        if (!started || connecting) return
        if (controller != null) return
        val list = candidates
        if (list.isEmpty()) {
            emitIdle()
            return
        }
        if (candidateIndex !in list.indices) candidateIndex = 0
        val target = list[candidateIndex]
        activeCandidate = target
        connecting = true
        teardownBrowser()
        bindFailed = false
        // 有些 ROM 既不回调 onConnected 也不回调 onConnectionFailed，自己兜底。
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        try {
            val next = MediaBrowser(context.applicationContext, target, connectionCallback, null)
            browser = next
            next.connect()
        } catch (error: Exception) {
            Diagnostics.log(
                "[MediaSource] 绑定蓝牙 MediaBrowser 失败：" +
                    "${target.flattenToShortString()} $error",
            )
            switchCandidateOrRetry(error.javaClass.simpleName)
        }
    }

    private val connectTimeout = Runnable {
        if (!connecting) return@Runnable
        switchCandidateOrRetry("超时")
    }

    /** 当前候选连不上：还有别的候选就换一个，都试完了再等下一轮。 */
    private fun switchCandidateOrRetry(reason: String) {
        connecting = false
        handler.removeCallbacks(connectTimeout)
        teardownBrowser()
        emitIdle()
        failedThisRound++
        val list = candidates
        val failed = activeCandidate?.flattenToShortString() ?: "候选"
        if (list.size > 1 && failedThisRound < list.size) {
            candidateIndex = (candidateIndex + 1) % list.size
            Diagnostics.log(
                "[MediaSource] 蓝牙候选 $failed $reason，换下一个：" +
                    list[candidateIndex].flattenToShortString(),
            )
            handler.removeCallbacks(connectRunnable)
            handler.postDelayed(connectRunnable, NEXT_CANDIDATE_DELAY_MS)
            return
        }
        candidateIndex = 0
        failedThisRound = 0
        bindFailed = true
        lastFailure = reason
        Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 连接失败，准备重连")
        scheduleRetry(reason)
    }

    private fun scheduleRetry(reason: String?) {
        if (!started) return
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(connectTimeout)
        val delay = when {
            retryAttempt <= 0 -> 1_000L
            retryAttempt == 1 -> 3_000L
            retryAttempt <= 5 -> 10_000L
            // 反复连不上就别一直刷了，30 秒一次，随时还能恢复。
            else -> 30_000L
        }
        retryAttempt++
        if (reason != null) {
            Diagnostics.log("[MediaSource] 蓝牙 MediaBrowser 重试（$reason）：${delay / 1000}s 后")
        }
        handler.postDelayed(retryRunnable, delay)
    }

    private fun teardownController() {
        handler.removeCallbacks(ticker)
        try {
            controller?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
            // 已经断了
        }
        controller = null
        smoothedPosition = null
        localBaseElapsed = 0
    }

    private fun teardownBrowser() {
        try {
            browser?.disconnect()
        } catch (_: Exception) {
            // 已经断了
        }
        browser = null
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_PLAYING_MS)
    }

    private fun isPlaying(): Boolean =
        controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun emit(force: Boolean) {
        val info = buildInfo()
        val signature = signatureOf(info)
        if (!force && signature == lastSignature) return
        lastSignature = signature
        lastInfo = info

        val head = "${info.title}|${info.artist}|${info.album}|${info.playing}"
        if (head != lastLoggedHead) {
            lastLoggedHead = head
            Diagnostics.log(
                "[MediaSource] 蓝牙：${info.title} 歌手=${info.artist} 专辑=${info.album} " +
                    "播放中=${info.playing} 位置=${info.positionMs}ms",
            )
        }
        listener?.invoke(info)
    }

    private fun emitIdle() {
        val info = MediaInfo(hasSession = false, sessionCount = 0)
        lastInfo = info
        lastSignature = signatureOf(info)
        listener?.invoke(info)
    }

    private fun buildInfo(): MediaInfo {
        val current = controller
            ?: return MediaInfo(hasSession = false, sessionCount = 0)

        val metadata = current.metadata
        val state = current.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val rawPosition = positionMsOf(state, durationMs)
        return MediaInfo(
            hasSession = true,
            sessionCount = 1,
            packageName = current.packageName,
            appLabel = labelFor(current.packageName),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = artistOf(metadata),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = durationMs,
            positionMs = smoothPosition(rawPosition),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            artUri = "",
            artBytes = null,
        )
    }

    private fun artistOf(metadata: MediaMetadata?): String {
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (!artist.isNullOrEmpty()) return artist
        return metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
    }

    private fun positionMsOf(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val now = SystemClock.elapsedRealtime()
            val updatedAt = state.lastPositionUpdateTime
            val elapsed = when {
                updatedAt > 0 -> now - updatedAt
                localBaseElapsed > 0 -> now - localBaseElapsed
                else -> 0
            }
            position += (elapsed * state.playbackSpeed).toLong()
        }
        if (durationMs > 0) position = position.coerceIn(0, durationMs)
        return position.coerceAtLeast(0)
    }

    private fun updateLocalBase(state: PlaybackState?) {
        if (state == null) {
            localBaseElapsed = 0
            return
        }
        localBaseElapsed = SystemClock.elapsedRealtime()
    }

    private fun smoothPosition(position: Long): Long {
        val previous = smoothedPosition
        if (previous != null && isJitter(previous, position)) return previous
        smoothedPosition = position
        return position
    }

    private fun isJitter(previous: Long, position: Long): Boolean {
        if (position >= previous) return false
        if (position < 1_000 && previous >= 1_000) return false
        return previous - position < 2_000
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
        append(info.playing)
    }

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

    fun play() {
        try {
            controller?.transportControls?.play()
        } catch (error: Exception) {
            Log.w(TAG, "play 失败", error)
        }
    }

    fun pause() {
        try {
            controller?.transportControls?.pause()
        } catch (error: Exception) {
            Log.w(TAG, "pause 失败", error)
        }
    }

    fun next() {
        try {
            controller?.transportControls?.skipToNext()
        } catch (error: Exception) {
            Log.w(TAG, "next 失败", error)
        }
    }

    fun previous() {
        try {
            controller?.transportControls?.skipToPrevious()
        } catch (error: Exception) {
            Log.w(TAG, "previous 失败", error)
        }
    }

    fun seek(positionMs: Long) {
        try {
            controller?.transportControls?.seekTo(positionMs)
        } catch (error: Exception) {
            Log.w(TAG, "seek 失败", error)
        }
    }

    fun diagnoseText(): String {
        val out = StringBuilder()
        val current = controller
        out.appendLine("【蓝牙 MediaBrowser】")
        out.appendLine("  服务支持：${isSupported()}（候选 ${candidates.size} 个）")
        out.appendLine("  已连接：${current != null}  连接中：$connecting  失败轮次：$failedThisRound")
        activeCandidate?.let { out.appendLine("  当前候选：${it.flattenToShortString()}") }
        if (candidates.isNotEmpty()) {
            out.appendLine("  候选服务：")
            candidates.forEachIndexed { index, item ->
                val mark = if (index == candidateIndex) "→" else "·"
                out.appendLine("    $mark ${item.flattenToShortString()}")
            }
        }
        if (current == null) {
            out.appendLine("  controller：无")
            failureReason()?.let { out.appendLine("  原因：$it") }
            return out.toString()
        }
        out.appendLine("  包名：${current.packageName}")
        val metadata = current.metadata
        if (metadata == null) {
            out.appendLine("  元数据：无")
        } else {
            out.appendLine("  元数据 ${metadata.size()} 项：")
            for (key in metadata.keySet()) {
                out.appendLine("    $key = ${describeValue(metadata, key)}")
            }
        }
        val state = current.playbackState
        out.appendLine(
            if (state == null) {
                "  PlaybackState：无"
            } else {
                "  PlaybackState：state=${state.state} position=${state.position} " +
                    "speed=${state.playbackSpeed} actions=${state.actions}"
            },
        )
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
        private const val TAG = "BluetoothBrowserSource"
        private const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        private const val TICK_PLAYING_MS = 250L
        private const val TICK_IDLE_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val NEXT_CANDIDATE_DELAY_MS = 600L

        /** ROM 可能换包名，常见的都试一下。 */
        private val BLUETOOTH_PACKAGES = listOf(
            BLUETOOTH_PACKAGE,
            "com.byd.bluetooth",
            "com.byd.bt",
            "com.qualcomm.bluetooth",
        )

        /** 已知的两种实现：高通 a2dpsink 和 AOSP avrcpcontroller。 */
        private val KNOWN_COMPONENTS = listOf(
            ComponentName(
                BLUETOOTH_PACKAGE,
                "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService",
            ),
            ComponentName(
                BLUETOOTH_PACKAGE,
                "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService",
            ),
        )
    }
}
