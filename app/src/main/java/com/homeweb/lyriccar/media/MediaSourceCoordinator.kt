package com.homeweb.lyriccar.media

import android.content.Context
import com.homeweb.lyriccar.model.MediaInfo
import com.homeweb.lyriccar.model.hasRealTrack

/**
 * 媒体来源协调器：蓝牙 MediaBrowser 优先，通知监听回退。
 *
 * 蓝牙 A2DP Sink 的 MediaBrowserService 在本车机上不需要通知使用权；
 * 其他 ROM 上如果服务不存在/连接失败，再退回原来的 NotificationListenerService。
 * 引擎、歌词库、界面都不需要知道数据来自哪一条路。
 */
class MediaSourceCoordinator(context: Context) {

    private val appContext = context.applicationContext
    private val browser = BluetoothBrowserSource(appContext)
    private val notification = MediaSessionRepository(appContext)

    @Volatile
    var listener: ((MediaInfo) -> Unit)? = null

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
        return when {
            isRealTrack(bt) -> Source.BROWSER to bt
            isRealTrack(nls) -> Source.NOTIFICATION to nls
            bt.hasRealTrack() -> Source.BROWSER to bt
            nls.hasSession -> Source.NOTIFICATION to nls
            bt.hasSession -> Source.BROWSER to bt
            else -> Source.NOTIFICATION to nls
        }
    }

    /** 过滤蓝牙未连接时的 "Not Provided / Unavailable" 占位标题。 */
    private fun isRealTrack(info: MediaInfo): Boolean = info.hasRealTrack()

    private fun controlsUseBrowser(): Boolean {
        if (active == Source.BROWSER && browser.hasSession()) return true
        if (active == Source.NOTIFICATION && latestNotification.hasRealTrack()) return false
        return browser.hasSession()
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
