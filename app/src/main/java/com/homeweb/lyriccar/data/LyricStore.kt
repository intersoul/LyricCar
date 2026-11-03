package com.homeweb.lyriccar.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.homeweb.lyriccar.core.LyricSink
import com.homeweb.lyriccar.model.LyricSource
import com.homeweb.lyriccar.model.StoredSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 应用私有目录：歌词库、设置、诊断日志都放这儿（与旧 Flutter 版完全一致）。 */
fun carlyricDirectory(context: Context): File =
    File(context.filesDir, "carlyric").apply { if (!exists()) mkdirs() }

/**
 * 歌词库（本地持久化 + 导出 / 导入）。
 *
 * 存成 <files>/carlyric/lyrics.json，与 Flutter 版格式一致，所以覆盖安装后
 * 旧歌词库能直接读出来。写入做了防抖：第一次变化 300ms 后写，连续采集时
 * 每 8 秒最多整份重写一次；「新歌第一句 / 采齐 / 删歌 / 导入 / 退到后台」
 * 仍会尽快写。
 */
class LyricStore private constructor(
    private val file: File,
    private val songs: MutableMap<String, StoredSong> = LinkedHashMap(),
    private val minWriteIntervalMs: Long = MIN_WRITE_INTERVAL_MS,
) : LyricSink, LyricLibrary {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lyriccar-store-writer").apply { isDaemon = true }
    }

    private var saveScheduled = false
    @Volatile
    private var dirty = false
    private var lastWriteAt = 0L
    private var closed = false

    @Volatile
    var revision: Int = 0
        private set

    /** 歌词库内容变化时回调（歌词库面板打开时用来刷新列表）。 */
    private val listeners = mutableSetOf<() -> Unit>()

    fun addOnChangedListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeOnChangedListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private val saveRunnable = Runnable {
        saveScheduled = false
        writeAsync()
    }

    // ----------------------------------------------------------
    // LyricSink
    // ----------------------------------------------------------
    @Synchronized
    override fun find(key: String): StoredSong? = songs[key]

    override val all: Collection<StoredSong>
        get() = synchronized(this) { songs.values.toList() }

    override val count: Int
        get() = synchronized(this) { songs.size }

    @Synchronized
    override fun put(song: StoredSong) {
        val existing = songs[song.key]
        songs[song.key] = song
        bump()
        scheduleSave(urgent = existing == null || (!existing.complete && song.complete))
    }

    @Synchronized
    override fun remove(key: String): Boolean {
        val removed = songs.remove(key) != null
        if (removed) {
            bump()
            scheduleSave(urgent = true)
        }
        return removed
    }

    @Synchronized
    fun clear() {
        songs.clear()
        bump()
        scheduleSave(urgent = true)
    }

    /** 全部歌词，按最近更新排序。 */
    @Synchronized
    fun songs(): List<StoredSong> = songs.values.sortedByDescending { it.updatedAtMs }

    private fun bump() {
        revision++
        val snapshot = synchronized(listeners) { listeners.toList() }
        if (snapshot.isEmpty()) return
        val dispatch = { snapshot.forEach { listener -> runCatching { listener() } } }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch() else mainHandler.post(dispatch)
    }

    // ----------------------------------------------------------
    // 导出 / 导入
    // ----------------------------------------------------------
    @Synchronized
    fun exportDocument(): JSONObject {
        val array = JSONArray()
        songs().forEach { array.put(it.toJson()) }
        return JSONObject().apply {
            put("app", "lyriccar")
            put("version", DOCUMENT_VERSION)
            put("exportedAtMs", System.currentTimeMillis())
            put("count", songs.size)
            put("songs", array)
        }
    }

    override fun exportJson(): String = exportDocument().toString(2)

    /** 把导出的 JSON 合并进当前歌词库；好的版本压过差的版本。 */
    override fun importJson(text: String): ImportResult {
        val decoded = try {
            JSONObject(text)
        } catch (error: Exception) {
            return ImportResult(error = "不是合法的 JSON（${error.message}）")
        }
        val raw = decoded.optJSONArray("songs")
            ?: return ImportResult(error = "文件里没有 songs 列表")

        var added = 0
        var updated = 0
        var skipped = 0
        synchronized(this) {
            for (i in 0 until raw.length()) {
                val incoming = StoredSong.fromJson(raw.opt(i))
                if (incoming == null || !incoming.usable) {
                    skipped++
                    continue
                }
                val marked = incoming.copy(source = LyricSource.IMPORTED)
                val existing = songs[marked.key]
                if (existing == null) {
                    songs[marked.key] = marked
                    added++
                } else if (preferIncoming(existing, marked)) {
                    songs[marked.key] = marked
                    updated++
                } else {
                    skipped++
                }
            }
            if (added > 0 || updated > 0) {
                bump()
                scheduleSave(urgent = true)
            }
        }
        return ImportResult(added = added, updated = updated, skipped = skipped)
    }

    private fun preferIncoming(existing: StoredSong, incoming: StoredSong): Boolean {
        if (incoming.ready != existing.ready) return incoming.ready
        if (incoming.lines.size != existing.lines.size) {
            return incoming.lines.size > existing.lines.size
        }
        return incoming.complete && !existing.complete
    }

    // ----------------------------------------------------------
    // 落盘
    // ----------------------------------------------------------
    private fun scheduleSave(urgent: Boolean) {
        dirty = true
        if (closed) return
        if (saveScheduled && !urgent) return
        mainHandler.removeCallbacks(saveRunnable)
        saveScheduled = false
        if (urgent) {
            writeAsync()
            return
        }
        val since = if (lastWriteAt == 0L) null else System.currentTimeMillis() - lastWriteAt
        val wait = if (since == null || since >= minWriteIntervalMs) {
            FIRST_WRITE_DELAY_MS
        } else {
            minWriteIntervalMs - since
        }
        saveScheduled = true
        mainHandler.postDelayed(saveRunnable, wait)
    }

    /** 强制立刻落盘（退到后台时用）；等写盘完成，避免刚退后台就被系统杀掉。 */
    fun flush() {
        mainHandler.removeCallbacks(saveRunnable)
        saveScheduled = false
        try {
            writer.submit { writeNow() }.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // 写盘失败不能让 Activity 生命周期卡死；dirty 标记会留给下一次
        }
    }

    private fun writeAsync() {
        writer.execute { writeNow() }
    }

    private fun writeNow() {
        if (!dirty) return
        val document = synchronized(this) {
            if (!dirty) return
            dirty = false
            exportDocument().toString(2)
        }
        lastWriteAt = System.currentTimeMillis()
        try {
            val temp = File(file.path + ".tmp")
            temp.writeText(document)
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (error: Exception) {
            dirty = true
            Diagnostics.current?.log("歌词库写入失败：$error")
        }
    }

    /** 关闭线程池（Application 结束时并不需要，但测试/重建时可显式调用）。 */
    fun close() {
        closed = true
        mainHandler.removeCallbacks(saveRunnable)
        writeAsync()
        writer.shutdown()
    }

    companion object {
        const val DOCUMENT_VERSION = 1
        const val FILE_NAME = "lyrics.json"
        const val FIRST_WRITE_DELAY_MS = 300L
        const val MIN_WRITE_INTERVAL_MS = 8_000L

        /** 打开（或新建）歌词库。 */
        fun open(directory: File): LyricStore {
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, FILE_NAME)
            val songs = LinkedHashMap<String, StoredSong>()
            if (file.exists()) {
                try {
                    val decoded = JSONObject(file.readText())
                    val raw = decoded.opt("songs")
                    when (raw) {
                        is JSONArray -> {
                            for (i in 0 until raw.length()) {
                                StoredSong.fromJson(raw.opt(i))?.let { songs[it.key] = it }
                            }
                        }
                        is JSONObject -> {
                            val keys = raw.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                StoredSong.fromJson(raw.opt(key))?.let { songs[it.key] = it }
                            }
                        }
                    }
                } catch (error: Exception) {
                    Diagnostics.current?.log("歌词库解析失败，已忽略：$error")
                }
            }
            return LyricStore(file, songs)
        }
    }
}
