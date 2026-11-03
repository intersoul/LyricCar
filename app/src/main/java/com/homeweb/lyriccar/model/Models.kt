package com.homeweb.lyriccar.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 系统媒体会话里读到的「正在播放」信息。
 *
 * 字段名与 MediaMetadata 的 METADATA_KEY_* 一一对应；位置由原生侧按
 * 「最后位置 + 经过时间 × 倍速」外推，并在 MediaSessionRepository 里做单调平滑。
 */
data class MediaInfo(
    val hasSession: Boolean = false,
    val sessionCount: Int = 0,
    val packageName: String = "",
    val appLabel: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val artUri: String = "",
    val artBytes: ByteArray? = null,
) {
    val hasTrack: Boolean get() = hasSession && title.trim().isNotEmpty()

    fun withPosition(position: Long): MediaInfo = copy(positionMs = position)

    companion object {
        val NONE = MediaInfo()
    }
}

/**
 * 是不是真的有一首歌。
 *
 * 蓝牙没连上时车机蓝牙栈会把 "Not Provided" / "Unavailable" 当成歌名报上来，
 * 这种占位标题不能参与「谁在播」的挑选，否则会把真正在放歌的会话挤下去。
 */
fun MediaInfo.hasRealTrack(): Boolean {
    if (!hasSession) return false
    val value = title.trim()
    if (value.isEmpty()) return false
    return !value.equals("Not Provided", ignoreCase = true) &&
        !value.equals("Unavailable", ignoreCase = true)
}

/** 一句歌词。时间戳是播放器当时的播放位置（毫秒）。 */
data class LyricLine(val timeMs: Long, val text: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", timeMs)
        put("text", text)
    }

    companion object {
        fun fromJson(raw: Any?): LyricLine? {
            val obj = raw as? JSONObject ?: return null
            val text = obj.optString("text").trim()
            if (text.isEmpty()) return null
            val ms = when (val value = obj.opt("t")) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            } ?: return null
            return LyricLine(ms, text)
        }
    }
}

/** 播放到 position 时应该高亮第几句；-1 = 还没到第一句。 */
fun lyricIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    if (positionMs < lines.first().timeMs) return -1
    var low = 0
    var high = lines.lastIndex
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (lines[mid].timeMs <= positionMs) low = mid else high = mid - 1
    }
    return low
}

/** 同一句重采的时间容差（毫秒）。 */
const val LYRIC_MERGE_WINDOW_MS = 1500L

/** 歌词库里少于这么多句的记录算「没采到什么东西」。 */
const val MIN_LIBRARY_LINES = 5

/** 歌词库里只覆盖了这么短一段播放时间的记录算「听了个开头就跳走了」。 */
const val MIN_LIBRARY_SPAN_MS = 10_000L

/**
 * 把一句新采到的歌词并进 lines。
 *
 * 文本和时间都对得上已有某句 → 视为重采，原样返回**同一个列表实例**，
 * 调用方据此判断「有没有新句子」。
 */
fun mergeLyricLine(lines: List<LyricLine>, incoming: LyricLine): List<LyricLine> {
    for (line in lines) {
        if (line.text != incoming.text) continue
        if (abs(line.timeMs - incoming.timeMs) <= LYRIC_MERGE_WINDOW_MS) return lines
    }
    val merged = ArrayList<LyricLine>(lines.size + 1)
    merged.addAll(lines)
    merged.add(incoming)
    merged.sortBy { it.timeMs }
    return merged
}

/** 算作「从头开始听」的位置容差。 */
const val FROM_START_TOLERANCE_MS = 3000L

enum class LyricSource(val label: String) {
    CAPTURED("采集"),
    IMPORTED("导入");

    companion object {
        fun fromName(name: String?): LyricSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CAPTURED
    }
}

/** 一首歌的完整歌词。 */
data class StoredSong(
    val key: String,
    val title: String,
    val songName: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val source: LyricSource = LyricSource.CAPTURED,
    val complete: Boolean = false,
    val fromStart: Boolean = false,
    val updatedAtMs: Long = 0L,
    val lines: List<LyricLine> = emptyList(),
) {
    /** 至少两句才算有歌词 —— 只抓到一句多半是把真歌手 / 真专辑误当歌词了。 */
    val usable: Boolean get() = lines.size >= 2

    /** 存下来的歌词覆盖了多长一段播放时间（第一句到最末一句）。 */
    val spanMs: Long
        get() = if (lines.size < 2) 0L else lines.last().timeMs - lines.first().timeMs

    /**
     * 值不值得留在歌词库。
     *
     * 「不到 5 句」或者「歌词只覆盖了 10 秒以内」的多半是滑过、跳过的歌 ——
     * 纯音乐那种只有两行作曲 / 作词信息的也算在内，留着也唱不出歌词。
     * 用户自己导入的不动。每次启动时清掉这些，不必占着歌词库。
     */
    val weakForLibrary: Boolean
        get() {
            if (source == LyricSource.IMPORTED) return false
            return lines.size < MIN_LIBRARY_LINES || spanMs < MIN_LIBRARY_SPAN_MS
        }

    val displayName: String get() = songName.trim().ifEmpty { title }

    /** 拿来和播放器报的歌名比对的名字。 */
    val matchName: String
        get() = songName.trim().ifEmpty {
            songNameFromTitleArtistLine(title) ?: title.trim()
        }

    val missingStart: Boolean get() = !fromStart
    val ready: Boolean get() = complete && fromStart && usable
    val replayable: Boolean get() = usable
    val needsWork: Boolean get() = !complete || !fromStart

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("title", title)
        put("songName", songName)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("source", source.name.lowercase())
        put("complete", complete)
        put("fromStart", fromStart)
        put("updatedAtMs", updatedAtMs)
        put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(raw: Any?): StoredSong? {
            val obj = raw as? JSONObject ?: return null
            val title = obj.optString("title")
            val durationMs = obj.optLong("durationMs")
            val rawKey = obj.optString("key")
            val key = rawKey.ifEmpty { songKey(title, durationMs) }

            val array = obj.optJSONArray("lines") ?: return null
            val lines = ArrayList<LyricLine>(array.length())
            for (i in 0 until array.length()) {
                LyricLine.fromJson(array.opt(i))?.let { lines.add(it) }
            }
            if (lines.isEmpty()) return null
            lines.sortBy { it.timeMs }

            val fromStart = if (obj.has("fromStart") && !obj.isNull("fromStart")) {
                obj.optBoolean("fromStart")
            } else {
                lines.first().timeMs <= FROM_START_TOLERANCE_MS
            }

            return StoredSong(
                key = key,
                title = title,
                songName = obj.optString("songName"),
                artist = obj.optString("artist"),
                album = obj.optString("album"),
                durationMs = durationMs,
                source = LyricSource.fromName(obj.optString("source")),
                complete = obj.optBoolean("complete"),
                fromStart = fromStart,
                updatedAtMs = obj.optLong("updatedAtMs"),
                lines = lines,
            )
        }
    }
}

/** 歌词写在媒体信息的哪一行。 */
enum class LyricFieldPreference(val label: String) {
    AUTO("自动判断"),
    ARTIST("歌手行"),
    ALBUM("专辑行"),
    TITLE("歌曲名行");

    val usesTitleRow: Boolean get() = this == TITLE

    companion object {
        fun fromName(name: String?): LyricFieldPreference =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

/** 界面配色。深色是默认，浅色给白天 / 反光严重的车机用。 */
enum class ThemeMode(val label: String) {
    DARK("深色"),
    LIGHT("浅色");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DARK
    }
}

/**
 * 播放信息从哪条通道读。
 *
 * 有的车机（比如部分比亚迪）拿「媒体中心」当播放器：它报「正在播放」和进度，
 * 但 MediaSession 里的歌名 / 歌手是空的 —— 这时候系统会话这条路读不到歌，
 * 得直接问蓝牙栈（A2DP Sink 的 MediaBrowserService）要。
 */
enum class SourceChannel(val label: String) {
    /** 默认：蓝牙通道有真歌名就用它，否则读系统媒体会话。 */
    AUTO("自动"),

    /** 只读蓝牙通道：给「媒体中心放蓝牙音乐」这种系统会话没歌名的车机用。 */
    BLUETOOTH("蓝牙"),

    /** 只读系统媒体会话（媒体中心 / 网易云 / QQ 音乐自己报的）。 */
    SESSION("系统会话");

    companion object {
        fun fromName(name: String?): SourceChannel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

enum class LyricMode { IDLE, CAPTURING, REPLAY }

/** 采集引擎对外的状态快照。 */
class LyricSessionState(
    val media: MediaInfo = MediaInfo.NONE,
    val mode: LyricMode = LyricMode.IDLE,
    val song: StoredSong? = null,
    val lines: List<LyricLine> = emptyList(),
    val currentIndex: Int = -1,
    val complete: Boolean = false,
    val storedCount: Int = 0,
    val collecting: Boolean = false,
    val derivedPositionMs: Long? = null,
    val playingNow: Boolean = false,
    val positionStale: Boolean = false,
    /**
     * 左栏标题栏显示的歌名。
     *
     * 歌词写在歌曲名行的播放器（网易云 / QQ 音乐车载歌词）会把 media.title
     * 一句句换成歌词，所以标题栏不能用它；这里给的是固定的歌名，
     * 认不出来时为空（界面显示「未知歌名」），不再跟着歌词一起变。
     */
    val songTitle: String = "",
) {
    val lineCount: Int get() = lines.size
    val hasLyrics: Boolean get() = lines.isNotEmpty()
    val fromStart: Boolean get() = song?.fromStart ?: false
    val ready: Boolean get() = song?.ready ?: false
}

// ------------------------------------------------------------------
// 歌曲标识 / 名字归一化
// ------------------------------------------------------------------

fun normalizeTitle(title: String): String =
    title.trim().lowercase().replace(Regex("\\s+"), " ")

/**
 * 从 `歌曲名 - 歌手` 里切出真歌名；切不出来返回 null。
 * 按第一个分隔符切，前半段和后半段都必须非空。
 */
fun songNameFromTitleArtistLine(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    for (separator in listOf(" - ", " – ", " — ")) {
        val index = trimmed.indexOf(separator)
        if (index <= 0) continue
        val name = trimmed.substring(0, index).trim()
        val rest = trimmed.substring(index + separator.length).trim()
        if (name.isEmpty() || rest.isEmpty()) continue
        return name
    }
    return null
}

/** 从 `歌曲名 - 歌手` 里切出歌手名；切不出来返回 null。 */
fun artistFromTitleArtistLine(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    for (separator in listOf(" - ", " – ", " — ")) {
        val index = trimmed.indexOf(separator)
        if (index <= 0) continue
        val name = trimmed.substring(0, index).trim()
        val rest = trimmed.substring(index + separator.length).trim()
        if (name.isEmpty() || rest.isEmpty()) continue
        return rest
    }
    return null
}

/** 「歌词写在歌曲名行」时用的歌曲标识：歌手 + 专辑 + 精确到秒的时长。 */
fun titleRowSongKey(artist: String, album: String, durationMs: Long): String {
    val seconds = durationMs / 1000
    val bucket = if (seconds <= 0) 0 else seconds
    return "${normalizeTitle(artist)}\u0000${normalizeTitle(album)}|$bucket"
}

/** 歌手行是不是 `歌曲名 - 歌手` 这种身份信息（不是歌词）。 */
fun looksLikeTitleArtistLine(text: String, title: String): Boolean {
    val head = normalizeTitle(title)
    if (head.isEmpty()) return false
    val value = normalizeTitle(text)
    if (!value.startsWith(head)) return false
    val rest = value.substring(head.length).trimStart()
    return rest.startsWith("-") || rest.startsWith("–") || rest.startsWith("—")
}

/** 歌手行 / 专辑行模式下的歌曲标识：标题 + 5 秒时长桶。 */
fun songKey(title: String, durationMs: Long): String {
    val seconds = durationMs / 1000
    val bucket = if (seconds <= 0) 0L else (seconds.toDouble() / 5).roundToLong() * 5
    return "${normalizeTitle(title)}|$bucket"
}
