package com.homeweb.lyriccar.media

import android.content.Context
import com.homeweb.lyriccar.model.MediaInfo
import com.homeweb.lyriccar.model.SourceChannel
import com.homeweb.lyriccar.model.hasRealTrack

/**
 * 媒体来源协调器：蓝牙 MediaBrowser 与系统媒体会话两条通道，按设置调度。
 *
 * 默认「自动」：蓝牙通道有真歌名就用它，否则读系统会话（通知监听）。
 * 有些车机（比如用「媒体中心」放蓝牙音乐）系统会话里没有歌名，
 * 用户可以在设置里把通道手动切成「蓝牙」；反过来也能只读系统会话。
 *
 * 引擎、歌词库、界面都不需要知道数据来自哪一条路。
 */
class MediaSourceCoordinator(
    context: Context,
    initialChannel: SourceChannel = SourceChannel.AUTO,
) {

    private val appContext = context.applicationContext
    private val browser = BluetoothBrowserSource(appContext)
    private val notification = MediaSessionRepository(appContext)

    @Volatile
    var listener: ((MediaInfo) -> Unit)? = null

    @Volatile
    private var channel: SourceChannel = initialChannel

    private var latestBrowser = MediaInfo.NONE
    private var latestNotification = MediaInfo.NONE
    private var active = Source.NOTIFICATION
    private var lastSignature: String? = null
    private var started = false

    init {
        browser.listener = { info ->
            latestBrowser = info
            dispatch()
        }
        notification.listener = { info ->
            latestNotification = info
            dispatch()
        }
    }

    fun start() {
        if (started) return
        started = true
        notification.start()
        browser.start()
    }

    fun stop() {
        started = false
        browser.stop()
        notification.stop()
    }

    fun currentChannel(): SourceChannel = channel

    /** 用户在设置里换通道：立刻按新通道重新选一次数据。 */
    fun setChannel(next: SourceChannel) {
        if (channel == next) return
        channel = next
        lastSignature = null
        if (next == SourceChannel.BLUETOOTH) browser.checkAndReconnect()
        dispatch()
    }

    /** 蓝牙通道现在到底有没有在出货（设置面板 / 状态行用来解释「为什么没歌」）。 */
    fun bluetoothConnected(): Boolean = browser.hasSession()

    fun channelStateText(): String = when (channel) {
        SourceChannel.AUTO -> "自动：蓝牙能拿到歌名就用蓝牙，否则读系统会话"
        SourceChannel.BLUETOOTH -> when {
            browser.hasSession() -> "蓝牙：已连上，正在读蓝牙播放信息"
            browser.isUsable() -> "蓝牙：等待手机通过蓝牙播放"
            else -> "蓝牙：这台车机没有蓝牙媒体浏览服务"
        }
        SourceChannel.SESSION -> "系统会话：读播放器自己报的媒体信息"
    }

    /** 蓝牙 MediaBrowser 是否可用 —— 界面据此隐藏通知使用权引导。 */
    fun hasAlternativeSource(): Boolean = browser.isUsable()

    /** 不可用时给用户看的原因（诊断 / 界面提示）。 */
    fun alternativeSourceFailure(): String? = browser.failureReason()

    fun checkAccessAndRearm() {
        browser.checkAndReconnect()
        notification.checkAccessAndRearm()
    }

    fun hasNotificationAccess(): Boolean = notification.hasNotificationAccess()

    fun listenerComponentName(): String = notification.listenerComponentName()

    fun openNotificationAccessSettings() = notification.openNotificationAccessSettings()

    fun diagnoseText(): String = buildString {
        append("【通道】${channel.label}（${channelStateText()}）\n\n")
        append(browser.diagnoseText().trimEnd())
        append("\n\n")
        append("【通知监听回退】\n")
        append(notification.diagnoseText().trimEnd())
    }

    private fun dispatch() {
        val (source, info) = choose()
        val signature = "${source.name}|${signatureOf(info)}"
        if (signature == lastSignature) return
        lastSignature = signature
        active = source
        listener?.invoke(info)
    }

    private fun choose(): Pair<Source, MediaInfo> {
        val bt = latestBrowser
        val nls = latestNotification
        return when (channel) {
            SourceChannel.BLUETOOTH -> Source.BROWSER to bt
            SourceChannel.SESSION -> Source.NOTIFICATION to nls
            SourceChannel.AUTO -> when {
                isRealTrack(bt) -> Source.BROWSER to bt
                isRealTrack(nls) -> Source.NOTIFICATION to nls
                bt.hasRealTrack() -> Source.BROWSER to bt
                nls.hasSession -> Source.NOTIFICATION to nls
                bt.hasSession -> Source.BROWSER to bt
                else -> Source.NOTIFICATION to nls
            }
        }
    }

    /** 过滤蓝牙未连接时的 "Not Provided / Unavailable" 占位标题。 */
    private fun isRealTrack(info: MediaInfo): Boolean = info.hasRealTrack()

    private fun controlsUseBrowser(): Boolean = when (channel) {
        SourceChannel.BLUETOOTH -> true
        SourceChannel.SESSION -> false
        SourceChannel.AUTO -> {
            if (active == Source.BROWSER && browser.hasSession()) return true
            if (active == Source.NOTIFICATION && latestNotification.hasRealTrack()) return false
            browser.hasSession()
        }
    }

    fun play() {
        if (controlsUseBrowser()) browser.play() else notification.play()
    }

    fun pause() {
        if (controlsUseBrowser()) browser.pause() else notification.pause()
    }

    fun next() {
        if (controlsUseBrowser()) browser.next() else notification.next()
    }

    fun previous() {
        if (controlsUseBrowser()) browser.previous() else notification.previous()
    }

    fun seek(positionMs: Long) {
        if (controlsUseBrowser()) browser.seek(positionMs) else notification.seek(positionMs)
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
        append(info.artUri).append('\u0000')
        append(System.identityHashCode(info.artBytes))
    }

    private enum class Source { BROWSER, NOTIFICATION }
}
