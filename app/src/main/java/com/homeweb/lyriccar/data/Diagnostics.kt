package com.homeweb.lyriccar.data

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.homeweb.lyriccar.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 诊断日志。
 *
 * 所有 [LyricCar] 日志同时进 logcat 和 <files>/carlyric/diagnostics.log；
 * 超过 256 KB 滚成 .1（只留一份）。内存里保留最近 600 行供界面预览。
 */
class Diagnostics private constructor(
    private val file: File,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyriccar-diagnostics").apply { isDaemon = true }
    }
    private val lines = ArrayDeque<String>()
    private val pending = StringBuilder()
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        flushScheduled = false
        flush()
    }

    fun log(message: String, echo: Boolean = true) {
        val stamp = stamp()
        for (raw in message.split('\n')) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            val text = "$stamp $line"
            synchronized(lines) {
                lines.addLast(text)
                while (lines.size > MEMORY_LINES) lines.removeFirst()
            }
            synchronized(pending) { pending.append(text).append('\n') }
            if (echo) Log.i(TAG, text)
        }
        scheduleFlush()
    }

    fun section(title: String) = log("──── $title ────")

    fun check(label: String, ok: Boolean, detail: String? = null) {
        val mark = if (ok) "✓" else "✗"
        log(if (detail == null) "$mark $label" else "$mark $label：$detail")
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    /** 环境自查：系统、权限、以及原生侧能读到的每个会话。 */
    fun selfCheck(
        hasNotificationAccess: () -> Boolean,
        listenerComponent: () -> String,
        diagnose: () -> String,
        alternativeSource: () -> Boolean = { false },
        alternativeSourceFailure: () -> String? = { null },
        channelState: () -> String = { "" },
    ) {
        section("环境自查")
        log("App 版本 ${BuildConfig.VERSION_NAME}")
        log("运行时 Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        runCatching { channelState().takeIf { it.isNotBlank() } }
            .getOrNull()?.let { log("数据通道：$it") }
        val granted = try { hasNotificationAccess() } catch (error: Exception) { false }
        val alternative = try { alternativeSource() } catch (error: Exception) { false }
        when {
            granted -> check("通知使用权", true, "已授予")
            alternative -> log("• 通知使用权：未授予（当前使用蓝牙 MediaBrowser，不需要它）")
            else -> check("通知使用权", false, "未授予 —— 读不到别的 App 的媒体会话")
        }
        if (alternative) {
            log("蓝牙 MediaBrowser：可用（不需要通知使用权）")
        } else {
            val reason = try { alternativeSourceFailure() } catch (error: Exception) { null }
            log(
                "蓝牙 MediaBrowser：不可用" +
                    (if (reason.isNullOrBlank()) "" else "（$reason）") +
                    "，走通知监听回退",
            )
        }
        val component = try { listenerComponent() } catch (error: Exception) { "" }
        log("监听组件：${component.ifEmpty { "（拿不到）" }}")
        section("系统与会话")
        val dump = try { diagnose().trim() } catch (error: Exception) {
            "（读取失败：${error.message}）"
        }
        if (dump.isEmpty()) {
            log("（读不到会话信息，多半是通知使用权还没开）")
        } else {
            log(dump)
        }
    }

    /** 整份日志：先把攒着的写下去，再读文件；读不到退回内存。 */
    fun snapshot(): String {
        flushBlocking()
        try {
            if (file.exists()) {
                val text = file.readText()
                if (text.trim().isNotEmpty()) return text
            }
        } catch (_: Exception) {
            // 读不出来就用内存里的
        }
        return synchronized(lines) { lines.joinToString("\n") }
    }

    fun flush() {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        writer.execute { writeNow() }
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        synchronized(pending) { pending.clear() }
        flushBlocking()
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // 删不掉就算了
        }
        log("日志已清空")
    }

    private fun flushBlocking() {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        try {
            writer.submit { writeNow() }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 不让日志 IO 拖住界面
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        mainHandler.postDelayed(flushRunnable, FLUSH_DELAY_MS)
    }

    private fun writeNow() {
        val text = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toString().also { pending.setLength(0) }
        }
        val bounded = if (text.length > maxBytes) text.takeLast(maxBytes) else text
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val existing = if (file.exists()) file.length() else 0
            if (existing > 0 && existing + bounded.length > maxBytes) {
                val backup = File(file.path + ".1")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            file.appendText(bounded)
        } catch (_: Exception) {
            // 写不进去也不能让 App 挂掉，更不能在这里再打日志（会递归）
        }
    }

    companion object {
        const val TAG = "LyricCar"
        const val FILE_NAME = "diagnostics.log"
        const val DEFAULT_MAX_BYTES = 256 * 1024
        const val MEMORY_LINES = 600
        const val FLUSH_DELAY_MS = 1000L

        @Volatile
        var current: Diagnostics? = null
            private set

        /** 全局记录入口；日志还没装上时静默忽略。 */
        fun log(message: String, echo: Boolean = true) {
            current?.log(message, echo)
        }

        fun install(context: Context): Diagnostics {
            val dir = carlyricDirectory(context)
            val instance = Diagnostics(File(dir, FILE_NAME))
            current = instance
            instance.section("App 启动")
            instance.log("日志文件：${instance.file.path}")
            return instance
        }

        private fun stamp(): String {
            val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return "[${format.format(Date())}]"
        }
    }
}
