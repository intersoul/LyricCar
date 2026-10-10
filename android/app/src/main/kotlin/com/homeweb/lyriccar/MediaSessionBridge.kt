package com.homeweb.lyriccar

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
import android.util.Base64
import android.util.Log
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

/**
 * 「系统媒体会话」桥：把车机上别的 App（网易云 / QQ 音乐 / 车机自带蓝牙播放器）
 * 正在播什么，实时喂给 Flutter。
 *
 * 数据流：
 *   MediaSessionManager.getActiveSessions(监听器组件)   拿到全部会话
 *     → 挑一个「正在播的」当主角
 *     → MediaController.registerCallback                盯住它的元数据 / 播放状态
 *     → EventChannel "carlyric/media_stream"            推给 Dart
 * 反向（Dart → 播放器）走 MethodChannel "carlyric/media"：
 *   play / pause / next / previous / seek 都是转手调用 MediaController.transportControls，
 *   本 App 不碰音频，声音始终由车机自带播放器出。
 *
 * 两个坑：
 *   1. `getActiveSessions` 需要「通知使用权」，否则抛 SecurityException —— 全部 try 住，
 *      拿不到就当作没有会话，让界面去引导用户授权。
 *   2. 播放位置不会自己变化（回调只在状态变化时来），所以自己起一个 250ms 的 tick，
 *      按 `position + (now - lastPositionUpdateTime) * speed` 外推，歌词才能滚动。
 */
class MediaSessionBridge(
    private val context: Context,
    private val messenger: BinaryMessenger,
) : EventChannel.StreamHandler, MethodChannel.MethodCallHandler {

    companion object {
        private const val TAG = "MediaSessionBridge"
        private const val EVENT_CHANNEL = "carlyric/media_stream"
        private const val METHOD_CHANNEL = "carlyric/media"

        /** 播放中：歌词要跟着走，250ms 一次。 */
        private const val TICK_PLAYING_MS = 250L

        /** 没在播：位置不变，签名去重后其实一次都不会发，慢一点省电。 */
        private const val TICK_IDLE_MS = 1000L

        /** 封面压到这个边长以内再传给 Dart（车机屏幕用不着原图）。 */
        private const val ART_MAX_PX = 512

        /**
         * 每次元数据变化时把**所有键值**打进日志。
         *
         * 排查「歌词写在歌手行还是歌曲名行」「真歌名还在不在」时，这是决定性的证据：
         * 只看 TITLE / ARTIST / ALBUM 三个字段去猜是不够的 —— 真实播放器常把真歌名
         * 挪到 DISPLAY_TITLE / ALBUM_ARTIST，甚至塞自定义键。
         *
         * 平时用不着，嫌日志多就改成 false。
         */
        private const val DUMP_METADATA = true

        /** 日志里每个元数据值的截断长度，免得一句歌词把整行日志撑爆。 */
        private const val DUMP_VALUE_MAX = 60
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent =
        ComponentName(context, MediaSessionListenerService::class.java)

    private var events: EventChannel.EventSink? = null

    /** 系统里的全部会话；[controller] 是当前选中的那一个。 */
    private var controllers: List<MediaController> = emptyList()
    private var controller: MediaController? = null

    /** 上一次推出去的载荷签名，用来去重（见 [emit]）。 */
    private var lastSignature: String? = null

    /** 上一次打日志用的「标题/歌手/专辑/播放中」组合，避免位置 tick 刷屏。 */
    private var lastLoggedHead: String? = null

    /** 封面缓存：只解码一次，且只在**封面变化后**的第一帧随事件带给 Dart。 */
    private var artUri: String = ""
    private var artBase64: String? = null
    private var artSentForUri: String? = null

    private val labelCache = mutableMapOf<String, String>()

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            controllers = list.orEmpty()
            selectController(chooseController())
        }

    private val controllerCallback = object : MediaController.Callback() {
        // 这两个回调都按**载荷签名**去重（force = false）。真机上实测过：
        //   * 车机蓝牙栈每秒都会重发一次元数据（内容完全一样）；
        //   * 播放状态也每秒更新一次（位置在走）。
        // 如果这里用 force = true，就等于每秒无条件白推一次一模一样的载荷。
        // 去重是按整个载荷做的（标题/歌手/专辑/时长/位置/播放中/封面），
        // 所以「歌词换了一句」「暂停/播放」这种真变化一个都不会漏。
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            emit(force = false)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            emit(force = false)
        }

        override fun onSessionDestroyed() {
            refreshSessions()
            selectController(chooseController())
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            emit(force = false)
            handler.postDelayed(this, if (isPlaying()) TICK_PLAYING_MS else TICK_IDLE_MS)
        }
    }

    // ----------------------------------------------------------
    // 装配
    // ----------------------------------------------------------
    fun attach() {
        EventChannel(messenger, EVENT_CHANNEL).setStreamHandler(this)
        MethodChannel(messenger, METHOD_CHANNEL).setMethodCallHandler(this)
    }

    fun detach() {
        events = null
        handler.removeCallbacks(ticker)
        unregisterSessionsListener()
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    // ----------------------------------------------------------
    // EventChannel：Dart 订阅 / 退订
    // ----------------------------------------------------------
    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        events = sink
        lastSignature = null
        enableSessionsWatch()
        emit(force = true)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_PLAYING_MS)
    }

    override fun onCancel(arguments: Any?) {
        events = null
        handler.removeCallbacks(ticker)
        unregisterSessionsListener()
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    // ----------------------------------------------------------
    // MethodChannel：权限查询 / 播放控制
    // ----------------------------------------------------------
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "hasNotificationAccess" -> {
                val granted = hasNotificationAccess()
                Log.i(TAG, "通知使用权：$granted")
                // Dart 侧在 App 回到前台时会重新问一次 —— 用户很可能刚在系统设置里
                // 授权完回来。必须趁这个时候重新挂一遍会话监听：第一次挂的时候没有
                // 权限，退化成「只看自己的会话」，不重挂就得重启 App 才能读到别人。
                if (granted && events != null) enableSessionsWatch()
                result.success(granted)
            }

            "openNotificationAccessSettings" -> {
                openNotificationAccessSettings()
                result.success(null)
            }

            // 车机上有的 ROM（比如 BYD）把「通知使用权」设置页整个拿掉了，
            // 点进去只会弹「不支持该操作」。这时唯一的办法是在电脑上跑一条 adb，
            // 所以界面要把**确切的组件名**显示给用户，让他能直接复制粘贴。
            "listenerComponent" -> result.success(listenerComponent.flattenToString())

            "play" -> {
                controller?.transportControls?.play()
                result.success(null)
            }

            "pause" -> {
                controller?.transportControls?.pause()
                result.success(null)
            }

            "next" -> {
                controller?.transportControls?.skipToNext()
                result.success(null)
            }

            "previous" -> {
                controller?.transportControls?.skipToPrevious()
                result.success(null)
            }

            "seek" -> {
                val ms = (call.argument<Number>("positionMs"))?.toLong() ?: 0L
                controller?.transportControls?.seekTo(ms)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    // ----------------------------------------------------------
    // 会话选择
    // ----------------------------------------------------------
    private fun refreshSessions() {
        controllers = try {
            // 传组件名 = 请求「所有 App 的会话」，前提是本组件已启用为通知监听器
            sessionManager.getActiveSessions(listenerComponent).orEmpty()
        } catch (error: SecurityException) {
            // 还没给通知使用权 —— 不是错误，界面会去引导
            Log.i(TAG, "读不到媒体会话（缺通知使用权）：${error.message}")
            emptyList()
        } catch (error: Exception) {
            Log.w(TAG, "读媒体会话失败", error)
            emptyList()
        }
    }

    /**
     * 挑主角。**排序是有讲究的，真机上踩过坑**：
     *
     *   1. 正在播**且有歌名**的 —— 这才是「用户正在听的那首」；
     *   2. 有歌名（暂停中）—— 暂停时不要把界面清空；
     *   3. 还是上一次那个 —— 会话列表抖动的空档期保持住，别来回跳；
     *   4. 正在播但没歌名 —— BYD 的 `com.byd.mediacenter` 就是这种：明明报着
     *      `STATE_PLAYING`，metadata 却是空的（车机自己的音频通道）。它必须排在
     *      最后 —— 否则会把真正在放歌的那个蓝牙会话挤掉，界面一片空白。
     *
     * 这条顺序是实测出来的：只看「谁在播」就会选中那个空壳。
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
            emit(force = true)
            return
        }
        current?.unregisterCallback(controllerCallback)
        controller = next

        // 换歌 → 封面缓存作废
        artUri = ""
        artBase64 = null
        artSentForUri = null

        next?.registerCallback(controllerCallback, handler)
        Log.i(TAG, "选中会话：${describe(next)}")
        emit(force = true)
    }

    /** 打日志用的一行描述：谁在播、播什么、位置多深。 */
    private fun describe(target: MediaController?): String {
        if (target == null) return "（无）"
        val metadata = target.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        return "${labelFor(target.packageName)}[$title] 歌手=$artist 专辑=$album"
    }

    /**
     * 重新挂一遍会话监听并重挑主角。**必须可重复调用**：
     * App 启动时调一次（那时可能还没授权），用户授完权限回到前台再调一次。
     */
    private fun enableSessionsWatch() {
        unregisterSessionsListener()
        refreshSessions()
        Log.i(
            TAG,
            "发现 ${controllers.size} 个媒体会话：${controllers.joinToString("、") { it.packageName }}",
        )
        try {
            // 传组件名 = 请求「所有 App 的会话」，前提是本组件已启用为通知监听器
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                handler,
            )
        } catch (error: Exception) {
            // 还没给通知使用权时连监听都注册不上，退化成只看自己的会话。
            // 这不影响后面重新调一次 —— 用户授权后会被换回上面那条「全都能看」的。
            Log.i(TAG, "注册全量会话监听失败，退化为本应用会话：${error.message}")
            try {
                sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, null)
            } catch (inner: Exception) {
                Log.w(TAG, "注册会话监听彻底失败", inner)
            }
        }
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
    // 载荷构造 / 推送
    // ----------------------------------------------------------
    /**
     * 推一份状态给 Dart。
     *
     * [force] = true 只用于「订阅开始 / 换了会话」这类必须保证首次送达的场合；
     * 其余一律按签名去重 —— 位置每 tick 都在变会影响签名，但**没在播**时位置恒定，
     * 签名不变就一个字节都不发，这样 1 秒一次的 tick 也不会去骚扰蓝牙和界面。
     *
     * 日志只在「标题/歌手/专辑/播放中」真的变了时打一行：这三行正是歌词的落脚点，
     * 所以 `adb logcat -s MediaSessionBridge` 看到的就是「歌词送到第几句」的流水。
     */
    private fun emit(force: Boolean) {
        val sink = events ?: return
        val payload = buildPayload()
        val signature = payload.entries.joinToString("\u0000") { "${it.key}=${it.value}" }
        if (!force && signature == lastSignature) return
        lastSignature = signature

        val head = "${payload["title"]}|${payload["artist"]}|${payload["album"]}|${payload["playing"]}"
        if (head != lastLoggedHead) {
            lastLoggedHead = head
            Log.i(
                TAG,
                "推送：${payload["title"]} 歌手=${payload["artist"]} 专辑=${payload["album"]} 播放中=${payload["playing"]} 位置=${payload["positionMs"]}ms",
            )
            if (DUMP_METADATA) dumpMetadata(controller?.metadata)
        }
        sink.success(payload)
    }

    private fun buildPayload(): LinkedHashMap<String, Any?> {
        val payload = LinkedHashMap<String, Any?>()
        val current = controller

        if (current == null) {
            payload["hasSession"] = false
            payload["sessionCount"] = controllers.size
            return payload
        }

        val metadata = current.metadata
        val state = current.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        payload["hasSession"] = true
        payload["sessionCount"] = controllers.size
        payload["packageName"] = current.packageName
        payload["appLabel"] = labelFor(current.packageName)
        payload["title"] = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        payload["artist"] = artistOf(metadata)
        payload["album"] = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        payload["durationMs"] = durationMs
        payload["positionMs"] = positionMsOf(state, durationMs)
        payload["playing"] = state?.state == PlaybackState.STATE_PLAYING

        val uri = artUriOf(metadata)
        if (uri != artUri) {
            artUri = uri
            artBase64 = null
            artSentForUri = null
        }
        payload["artUri"] = artUri

        // 封面只在变化后发一次：它是几百 KB 的 base64，每个 tick 都发会把通道塞满
        if (artBase64 == null && artUri.isNotEmpty()) artBase64 = loadArt(metadata, artUri)
        val art = artBase64
        if (art != null && artSentForUri != artUri) {
            payload["artBase64"] = art
            artSentForUri = artUri
        }

        return payload
    }

    private fun artistOf(metadata: MediaMetadata?): String {
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        if (!artist.isNullOrEmpty()) return artist
        return metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
    }

    /**
     * 把整条元数据摊成一行日志（见 [DUMP_METADATA]）。
     */
    private fun dumpMetadata(metadata: MediaMetadata?) {
        if (metadata == null) {
            Log.i(TAG, "  元数据：空")
            return
        }
        val parts = metadata.keySet().map { key ->
            val short = key.removePrefix("android.media.metadata.")
            val value = metadata.getString(key)
                ?: if (metadata.getBitmap(key) != null) {
                    "<bitmap>"
                } else {
                    metadata.getLong(key).toString()
                }
            val trimmed = if (value.length > DUMP_VALUE_MAX) {
                value.take(DUMP_VALUE_MAX) + "…"
            } else {
                value
            }
            "$short=$trimmed"
        }
        Log.i(TAG, "  元数据：${parts.joinToString(" | ")}")
    }

    private fun artUriOf(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        return metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: ""
    }

    /**
     * 播放位置不会自己往前走，要按「最后一次更新的位置 + 经过的时间 × 倍速」外推。
     */
    private fun positionMsOf(state: PlaybackState?, durationMs: Long): Long {
        if (state == null) return 0L
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val updatedAt = state.lastPositionUpdateTime
            if (updatedAt > 0L) {
                val elapsed = SystemClock.elapsedRealtime() - updatedAt
                position += (elapsed * state.playbackSpeed).toLong()
            }
        }
        if (durationMs > 0L) position = position.coerceIn(0L, durationMs)
        return position.coerceAtLeast(0L)
    }

    private fun isPlaying(): Boolean = controller?.let { isPlaying(it) } ?: false

    private fun isPlaying(candidate: MediaController): Boolean =
        candidate.playbackState?.state == PlaybackState.STATE_PLAYING

    // ----------------------------------------------------------
    // 封面
    // ----------------------------------------------------------
    private fun loadArt(metadata: MediaMetadata?, uri: String): String? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: readBitmap(uri)

        if (bitmap == null) return null

        return try {
            val scaled = scaleDown(bitmap)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            // 缩放后的副本是我们自己造的，可以回收；原图属于播放器，碰不得
            if (scaled !== bitmap) scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= ART_MAX_PX) return bitmap
        val ratio = ART_MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun labelFor(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (error: Exception) {
            packageName
        }
        labelCache[packageName] = label
        return label
    }

    // ----------------------------------------------------------
    // 权限
    // ----------------------------------------------------------
    /**
     * 直接读 `enabled_notification_listeners` 系统设置项：
     * 比 NotificationManagerCompat 少一个依赖，而且这正是系统判断的依据。
     */
    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.packageName == context.packageName
        }
    }

    private fun openNotificationAccessSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 能直达本应用的开关页，用户少点两层
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    listenerComponent.flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            // 有的车机把详情页裁掉了，退回列表页
            Log.w(TAG, "打不开通知使用权详情页，改用列表页", error)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (inner: Exception) {
                Log.e(TAG, "打不开通知使用权设置", inner)
            }
        }
    }
}
