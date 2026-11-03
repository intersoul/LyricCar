package com.homeweb.lyriccar.core

import com.homeweb.lyriccar.model.FROM_START_TOLERANCE_MS
import com.homeweb.lyriccar.model.LyricFieldPreference
import com.homeweb.lyriccar.model.LyricLine
import com.homeweb.lyriccar.model.LyricMode
import com.homeweb.lyriccar.model.LyricSessionState
import com.homeweb.lyriccar.model.LyricSource
import com.homeweb.lyriccar.model.MediaInfo
import com.homeweb.lyriccar.model.StoredSong
import com.homeweb.lyriccar.model.looksLikeTitleArtistLine
import com.homeweb.lyriccar.model.lyricIndexAt
import com.homeweb.lyriccar.model.mergeLyricLine
import com.homeweb.lyriccar.model.normalizeTitle
import com.homeweb.lyriccar.model.songKey
import com.homeweb.lyriccar.model.songNameFromTitleArtistLine
import com.homeweb.lyriccar.model.titleRowSongKey
import java.util.Locale
import kotlin.math.abs

/** 采集引擎写歌词的地方（真正的实现是 data/LyricStore）。 */
interface LyricSink {
    fun find(key: String): StoredSong?
    val all: Collection<StoredSong>
    fun put(song: StoredSong)
    fun remove(key: String): Boolean
    val count: Int
}

/**
 * 歌词采集引擎。
 *
 * 车机不接管音频，只在旁边读系统媒体会话：播放器每换一句歌词就把当前这句写进
 * 歌手行 / 专辑行（部分播放器写歌曲名行）。这里盯着那一行的变化，变一次记一句，
 * 并记下当时的播放位置。下次再放同一首就按存下来的时间戳同步回放。
 *
 * 本类不碰任何 Android API，方便把采集规则单独验证。
 */
class LyricCaptureEngine(
    private val sink: LyricSink,
    field: LyricFieldPreference = LyricFieldPreference.AUTO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = POSITION_STALE_AFTER_MS,
    private val aliveWindowMs: Long = LYRIC_ALIVE_WINDOW_MS,
    private val log: (String) -> Unit = {},
) {
    private var field: LyricFieldPreference = field

    fun setField(value: LyricFieldPreference) {
        field = value
    }

    fun getField(): LyricFieldPreference = field

    // ── 当前会话 ──
    private var identity = ""
    private var autoTitleRow = false
    private var key = ""
    private var song: StoredSong? = null
    private var mode = LyricMode.IDLE
    private var lastArtist = ""
    private var lastAlbum = ""
    private var lastTitle = ""
    private var primedArtist = false
    private var primedAlbum = false
    private var primedTitle = false
    private var lastCapturedText = ""

    /** 这一次盯着这首歌时，是不是从开头就开始的。 */
    private var startedFromStart = false
    private var minPositionMs = 0L
    private var maxPositionMs = 0L
    private var songHadRecord = false

    private var positionChangedAtMs = 0L
    private var lyricSeenAtMs = 0L
    private var currentLyricText = ""
    private var textIndex: Int? = null
    private var loggedStuck = false

    private var realSongName = ""
    private var pendingTitleName = ""
    private var nameMatched = false
    private var nameMatchConfirmed = false
    private var nameConflicts = 0
    private var filling = false
    private var lastPositionMs = 0L
    private var startUndecided = false

    private var state = LyricSessionState()

    fun getState(): LyricSessionState = state

    private fun nowMs(): Long = clock()

    /** 歌词是否写在歌曲名行 —— 用户显式选的，或者「自动判断」当场识别出来的。 */
    private val titleRowMode: Boolean get() = this.field.usesTitleRow || autoTitleRow

    /** 喂一份媒体信息，返回最新状态。 */
    fun handle(info: MediaInfo): LyricSessionState {
        if (!info.hasTrack) {
            reset()
            publish(info, nowMs())
            return state
        }

        val now = nowMs()
        if (info.positionMs != lastPositionMs) positionChangedAtMs = now

        maybeAdoptTitleRow(info)

        val identityNow = identityOf(info)
        val songChange = identity.isNotEmpty() && (identityNow != identity || startsNewSong(info))
        if (identityNow != identity || startsNewSong(info)) {
            start(info, songChange)
        } else {
            refineKey(info)
            resolveStartUndecided(info)
            detectReplay(info)
        }

        trackLyricText(info, now)

        val verifying = nameMatched && !nameMatchConfirmed
        if (mode == LyricMode.CAPTURING || filling || verifying) {
            capture(info)
            maybeComplete(info)
        }

        lastArtist = info.artist
        lastAlbum = info.album
        lastTitle = info.title
        lastPositionMs = info.positionMs
        if (info.positionMs < minPositionMs) minPositionMs = info.positionMs
        if (info.positionMs > maxPositionMs) maxPositionMs = info.positionMs

        publish(info, now)
        return state
    }

    /**
     * 「自动判断」：标题变了、而播放位置没有明显往回退 → 歌词写在歌曲名行。
     * 0.75 秒容差留给原生侧位置外推的抖动。
     */
    private fun maybeAdoptTitleRow(info: MediaInfo) {
        if (field != LyricFieldPreference.AUTO) return
        if (autoTitleRow) return
        if (lastTitle.isEmpty()) return
        if (normalizeTitle(info.title) == normalizeTitle(lastTitle)) return
        if (info.positionMs + 750 < lastPositionMs) return
        val previous = (song?.durationMs ?: 0L) / 1000
        if (previous > 0 && info.durationMs / 1000 > 0) {
            if (abs(info.durationMs / 1000 - previous) > 2) return
        }

        autoTitleRow = true
        identity = identityOf(info)
        key = keyOf(info)
        val stored = sink.find(key)
        val base = stored ?: StoredSong(
            key = key,
            title = displayTitle(info),
            durationMs = info.durationMs,
            fromStart = startedFromStart,
            lines = song?.lines?.toList() ?: emptyList(),
        )
        realSongName = songNameFromTitleArtistLine(info.artist) ?: ""
        song = base.copy(
            key = key,
            title = displayTitle(info),
            songName = realSongName.ifEmpty { base.songName },
            durationMs = if (info.durationMs / 1000 > 0) info.durationMs else base.durationMs,
            fromStart = base.fromStart || startedFromStart,
            lines = base.lines.toList(),
        )
        lastCapturedText = base.lines.lastOrNull()?.text ?: ""
        primedTitle = true
        mode = if (base.replayable) LyricMode.REPLAY else LyricMode.CAPTURING
        syncFilling()

        val byName = matchByName(info)
        if (byName != null && byName.key != key) {
            song = byName
            key = byName.key
            mode = LyricMode.REPLAY
            nameMatched = true
            nameMatchConfirmed = false
            nameConflicts = 0
            syncFilling()
            lastCapturedText = ""
            log("[LyricCar] 按歌名匹配到《${byName.displayName}》→ 直接回放")
            return
        }
        log(
            "[LyricCar] 识别出歌词写在「歌曲名行」（标题在变、播放位置没回到开头），" +
                "已自动切换并改用「歌手 + 专辑 + 时长」认歌",
        )
    }

    /** 换歌时报的位置可能是上一首的僵尸值 —— 等它掉回开头再定。 */
    private fun resolveStartUndecided(info: MediaInfo) {
        if (!startUndecided) return
        val ms = info.positionMs
        if (ms > 20000) {
            startUndecided = false
            return
        }
        if (ms > 1000) return

        startUndecided = false
        startedFromStart = true
        val current = song ?: return
        if (current.fromStart) return
        val patched = current.copy(fromStart = true)
        song = patched
        sink.put(patched)
        log("[LyricCar] 位置掉回开头 → 这首其实是从头听起的，改标记")
    }

    /** 一首歌放完又从头放了一遍（单曲循环 / 手动重播）。 */
    private fun detectReplay(info: MediaInfo) {
        if (mode == LyricMode.IDLE) return
        val ms = info.positionMs
        if (lastPositionMs <= 0) return
        if (ms > 5000 || lastPositionMs < 30000) return
        log(
            "[LyricCar] 位置跳回开头（${lastPositionMs / 1000}s → " +
                "${fmtSeconds(ms)}s）：这首又从头放了一遍",
        )

        if (info.durationMs <= 0) ensureComplete()

        val current = song
        mode = if (current != null && current.replayable) LyricMode.REPLAY else LyricMode.CAPTURING
        syncFilling()

        startedFromStart = true
        if (current != null && !current.fromStart) {
            val patched = current.copy(fromStart = true)
            song = patched
            sink.put(patched)
        }

        primedArtist = false
        primedAlbum = false
        lastArtist = ""
        lastAlbum = ""
    }

    /** 这一次要不要一边回放一边补词。 */
    private fun syncFilling() {
        filling = mode == LyricMode.REPLAY && !(song?.ready ?: false)
    }

    private fun identityOf(info: MediaInfo): String =
        if (titleRowMode) {
            "${normalizeTitle(info.artist)}\u0000${normalizeTitle(info.album)}"
        } else {
            normalizeTitle(info.title)
        }

    private fun realNameOf(info: MediaInfo): String {
        songNameFromTitleArtistLine(info.artist)?.let { return it }
        if (field == LyricFieldPreference.AUTO || titleRowMode) return ""
        return info.title.trim()
    }

    /** 按真歌名在库里找一份能回放的稿子；找不到或有歧义返回 null。 */
    private fun matchByName(info: MediaInfo): StoredSong? {
        val name = realSongName.trim()
        if (name.isEmpty()) return null
        val target = normalizeTitle(name)
        val durationMs = info.durationMs
        val scored = ArrayList<StoredSong>()
        for (candidate in sink.all) {
            if (!candidate.replayable) continue
            val nameHit = normalizeTitle(candidate.matchName) == target ||
                normalizeTitle(candidate.title) == target
            if (!nameHit) continue
            val own = candidate.durationMs
            if (own > 0 && durationMs > 0 &&
                abs(own - durationMs) > NAME_MATCH_DURATION_TOLERANCE_MS
            ) {
                continue
            }
            scored.add(candidate)
        }
        if (scored.isEmpty()) return null
        if (scored.size == 1) return scored.single()
        if (durationMs <= 0) return null
        scored.sortBy { abs(it.durationMs - durationMs) }
        if (scored.size >= 2) {
            val gap = abs(scored[1].durationMs - scored[0].durationMs)
            if (gap <= NAME_MATCH_AMBIGUITY_MS) return null
        }
        return scored.first()
    }

    /** 歌名匹配到的稿子对不对 —— 用歌词本身验一句。 */
    private fun conflictsWithSong(candidate: StoredSong, positionMs: Long, text: String): Boolean {
        val lines = candidate.lines
        if (lines.size < 2) return false
        var near = 0
        var sameText = false
        for (line in lines) {
            if (abs(line.timeMs - positionMs) > NAME_CHECK_WINDOW_MS) continue
            near++
            if (line.text == text) sameText = true
        }
        if (near > 0) return !sameText
        return positionMs >= lines.first().timeMs - 2000 &&
            positionMs <= lines.last().timeMs + 2000
    }

    private fun verifyNameMatch(info: MediaInfo, text: String): Boolean {
        val current = song ?: return true
        if (!conflictsWithSong(current, info.positionMs, text)) {
            nameMatchConfirmed = true
            return true
        }
        nameConflicts++
        log("[LyricCar] 名字匹配对不上（第 $nameConflicts 次）：$text")
        if (nameConflicts < 2) return false
        rejectNameMatch(info)
        return false
    }

    private fun rejectNameMatch(info: MediaInfo) {
        nameMatched = false
        nameMatchConfirmed = false
        nameConflicts = 0

        val fallbackKey = keyOf(info)
        val fallback = sink.find(fallbackKey)
        val current = song
        if (fallback != null && fallback.replayable && fallback.key != (current?.key ?: "")) {
            key = fallbackKey
            song = fallback
            mode = LyricMode.REPLAY
            syncFilling()
            lastCapturedText = fallback.lines.last().text
            log("[LyricCar] 改按「换歌后那套 key」回放《${fallback.displayName}》")
            return
        }

        key = fallbackKey
        song = StoredSong(
            key = key,
            title = displayTitle(info),
            songName = realSongName,
            durationMs = info.durationMs,
            fromStart = startedFromStart,
            lines = emptyList(),
        )
        mode = LyricMode.CAPTURING
        filling = false
        primedTitle = true
        lastCapturedText = ""
        log("[LyricCar] 名字匹配不可信 → 按新稿采集")
    }

    private fun keyOf(info: MediaInfo): String =
        if (titleRowMode) {
            titleRowSongKey(info.artist, info.album, info.durationMs)
        } else {
            songKey(info.title, info.durationMs)
        }

    private fun displayTitle(info: MediaInfo): String {
        if (!titleRowMode) return info.title.trim()
        val artist = info.artist.trim()
        return if (artist.isNotEmpty()) artist else info.album.trim()
    }

    /** 歌手 / 专辑没变、只有时长变了 —— 歌曲名行模式下这是换歌的信号。 */
    private fun startsNewSong(info: MediaInfo): Boolean {
        if (!titleRowMode || mode == LyricMode.IDLE) return false
        val previous = (song?.durationMs ?: 0L) / 1000
        val now = info.durationMs / 1000
        if (previous <= 0 || now <= 0) return false
        return abs(now - previous) > 2
    }

    private fun start(info: MediaInfo, songChange: Boolean) {
        dropFleetingSong()

        autoTitleRow = false
        identity = identityOf(info)
        song = null
        lastCapturedText = ""
        key = keyOf(info)
        log(
            "[LyricCar] ${if (songChange) "换歌" else "起会话"}：key=$key 位置=" +
                "${fmtSeconds(info.positionMs)}s",
        )

        lastArtist = info.artist
        lastAlbum = info.album
        primedArtist = info.artist.trim().isNotEmpty()
        primedAlbum = info.album.trim().isNotEmpty()
        lastTitle = ""
        primedTitle = titleRowMode

        startedFromStart = info.positionMs <= FROM_START_TOLERANCE_MS
        startUndecided = songChange && !startedFromStart

        realSongName = realNameOf(info)
        // 只有「从头开始放」时，标题行上此刻那一句才可能是歌名 —— 半截插进来的歌，
        // 这一句多半是歌词，记成歌名会把左栏标题固定成一句歌词，丢掉。
        pendingTitleName =
            if (titleRowMode && realSongName.isEmpty() && startedFromStart) {
                info.title.trim()
            } else {
                ""
            }
        nameMatched = false
        nameMatchConfirmed = false
        nameConflicts = 0
        currentLyricText = ""
        textIndex = null

        minPositionMs = info.positionMs
        maxPositionMs = info.positionMs
        val stored = sink.find(key)
        songHadRecord = stored != null

        val byName = matchByName(info)
        if (byName != null) {
            song = byName
            key = byName.key
            songHadRecord = true
            mode = LyricMode.REPLAY
            nameMatched = true
            syncFilling()
            lastCapturedText = ""
            log("[LyricCar] 按歌名匹配到《${byName.displayName}》→ 直接回放")
            return
        }

        if (stored != null && stored.replayable) {
            song = stored
            mode = LyricMode.REPLAY
            syncFilling()
            lastCapturedText = stored.lines.last().text
            return
        }

        val seed = stored?.lines ?: emptyList()
        song = StoredSong(
            key = key,
            title = displayTitle(info),
            songName = realSongName.ifEmpty { stored?.songName ?: "" },
            artist = stored?.artist ?: "",
            album = stored?.album ?: "",
            durationMs = info.durationMs,
            source = stored?.source ?: LyricSource.CAPTURED,
            fromStart = (stored?.fromStart ?: false) || startedFromStart,
            lines = seed.toList(),
        )
        lastCapturedText = seed.lastOrNull()?.text ?: ""
        mode = LyricMode.CAPTURING
        syncFilling()
    }

    /** 时长后到 → key 变了，把已采的歌词搬到新 key 下。 */
    private fun refineKey(info: MediaInfo) {
        if (mode != LyricMode.CAPTURING) return
        if (info.durationMs / 1000 <= 0) return
        if (titleRowMode && (song?.durationMs ?: 0L) / 1000 > 0) return

        val newKey = keyOf(info)
        if (newKey == key) return

        val stored = sink.find(newKey)
        if (stored != null && stored.replayable) {
            key = newKey
            song = stored
            mode = LyricMode.REPLAY
            syncFilling()
            lastCapturedText = stored.lines.last().text
            return
        }

        val previousKey = key
        val base = song ?: StoredSong(
            key = newKey,
            title = displayTitle(info),
            lines = emptyList(),
        )
        val updated = base.copy(key = newKey, durationMs = info.durationMs)
        song = updated
        key = newKey
        if (previousKey != newKey) sink.remove(previousKey)
        if (updated.lines.isNotEmpty()) sink.put(updated)
    }

    /** 随手跳过的歌不留记录。 */
    private fun dropFleetingSong() {
        val current = song
        val hadRecord = songHadRecord
        val listenedMs = maxPositionMs - minPositionMs
        song = null
        songHadRecord = false
        minPositionMs = 0
        maxPositionMs = 0

        if (current == null || hadRecord) return
        if (listenedMs >= FLEETING_SKIP_THRESHOLD_MS) return
        if (sink.remove(current.key)) {
            log(
                "[LyricCar] 《${current.title}》只听了 ${listenedMs / 1000} 秒就切走了，不留记录",
            )
        }
    }

    private fun reset() {
        dropFleetingSong()
        identity = ""
        key = ""
        song = null
        mode = LyricMode.IDLE
        lastArtist = ""
        lastAlbum = ""
        lastTitle = ""
        primedArtist = false
        primedAlbum = false
        primedTitle = false
        lastCapturedText = ""
        lastPositionMs = 0
        startedFromStart = false
        startUndecided = false
        autoTitleRow = false
        songHadRecord = false
        filling = false
        realSongName = ""
        pendingTitleName = ""
        nameMatched = false
        nameMatchConfirmed = false
        nameConflicts = 0
        positionChangedAtMs = 0
        lyricSeenAtMs = 0
        currentLyricText = ""
        textIndex = null
        loggedStuck = false
        minPositionMs = 0
        maxPositionMs = 0
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------
    private fun capture(info: MediaInfo) {
        val picked = pickCandidate(info) ?: return
        val text = picked.second
        val primed = when (picked.first) {
            LyricFieldPreference.ARTIST -> primedArtist
            LyricFieldPreference.ALBUM -> primedAlbum
            LyricFieldPreference.TITLE -> primedTitle
            LyricFieldPreference.AUTO -> true
        }

        if (!primed) {
            when (picked.first) {
                LyricFieldPreference.ARTIST -> primedArtist = true
                LyricFieldPreference.ALBUM -> primedAlbum = true
                LyricFieldPreference.TITLE -> primedTitle = true
                LyricFieldPreference.AUTO -> Unit
            }
            return
        }

        if (text == lastCapturedText) return
        if (text.length > MAX_LINE_LENGTH) return

        if (titleRowMode && realSongName.isEmpty() && pendingTitleName.isNotEmpty() &&
            normalizeTitle(text) == normalizeTitle(pendingTitleName)
        ) {
            realSongName = text.trim()
            lastCapturedText = text
            log("[LyricCar] 记下真歌名：$realSongName")
            return
        }

        if (positionStuck(info, nowMs())) {
            if (!loggedStuck) {
                loggedStuck = true
                log(
                    "[LyricCar] 播放器不再报播放位置（卡在 ${info.positionMs / 1000}s）→ 只回放，先不采集",
                )
            }
            lastCapturedText = text
            return
        }

        if (nameMatched && !nameMatchConfirmed) {
            if (!verifyNameMatch(info, text)) return
            if (!filling) return
        }

        if (picked.first != LyricFieldPreference.TITLE &&
            looksLikeTitleArtistLine(text, info.title)
        ) {
            return
        }

        if (!titleRowMode && normalizeTitle(text) == normalizeTitle(info.title)) return

        val base = song ?: StoredSong(
            key = key,
            title = displayTitle(info),
            fromStart = startedFromStart,
            lines = emptyList(),
        )
        val lines = mergeLyricLine(base.lines, LyricLine(info.positionMs, text))
        val updated = base.copy(
            title = displayTitle(info),
            songName = realSongName.ifEmpty { base.songName },
            durationMs = if (info.durationMs / 1000 > 0) info.durationMs else base.durationMs,
            fromStart = base.fromStart || startedFromStart,
            lines = lines,
        )
        song = updated
        lastCapturedText = text
        sink.put(updated)
        if (lines !== base.lines) {
            log(
                "[LyricCar] +第 ${lines.size} 句 " +
                    "${fmtSeconds(info.positionMs)}s：$text",
            )
        }
    }

    /** 判断这一轮哪一行是歌词；null = 这轮没有新歌词。 */
    private fun pickCandidate(info: MediaInfo): Pair<LyricFieldPreference, String>? {
        val artist = info.artist.trim()
        val album = info.album.trim()
        val title = info.title.trim()
        val artistChanged = info.artist != lastArtist
        val albumChanged = info.album != lastAlbum
        val titleChanged = info.title != lastTitle

        if (titleRowMode) {
            return if (titleChanged && title.isNotEmpty()) {
                LyricFieldPreference.TITLE to title
            } else {
                null
            }
        }

        when (field) {
            LyricFieldPreference.TITLE ->
                if (titleChanged && title.isNotEmpty()) {
                    return LyricFieldPreference.TITLE to title
                }

            LyricFieldPreference.ARTIST ->
                if (artistChanged && artist.isNotEmpty()) {
                    return LyricFieldPreference.ARTIST to artist
                }

            LyricFieldPreference.ALBUM ->
                if (albumChanged && album.isNotEmpty()) {
                    return LyricFieldPreference.ALBUM to album
                }

            LyricFieldPreference.AUTO -> {
                if (artistChanged && albumChanged) {
                    if (artist.isNotEmpty() && artist == album) {
                        return LyricFieldPreference.ARTIST to artist
                    }
                    return null
                }
                if (artistChanged && artist.isNotEmpty()) {
                    return LyricFieldPreference.ARTIST to artist
                }
                if (albumChanged && album.isNotEmpty()) {
                    return LyricFieldPreference.ALBUM to album
                }
            }
        }
        return null
    }

    private fun maybeComplete(info: MediaInfo) {
        val total = info.durationMs
        if (total <= 0) return
        if (info.positionMs < total - 2000) return
        ensureComplete()
    }

    private fun ensureComplete() {
        val current = song ?: return
        if (current.complete || !current.usable) return
        if (!current.fromStart) return
        val done = current.copy(complete = true, updatedAtMs = nowMs())
        song = done
        sink.put(done)
        filling = false
    }

    /** 播放器报的位置是不是不可信了。 */
    private fun positionStuck(info: MediaInfo, now: Long): Boolean {
        if (now - lyricSeenAtMs > aliveWindowMs) return false
        val total = info.durationMs
        val position = info.positionMs
        if (total > 0 && position >= total - 500) return true
        if (positionChangedAtMs == 0L) return false
        return now - positionChangedAtMs > staleAfterMs
    }

    /** 记下刚看到的一句歌词，并在稿子里定位它（位置卡住时靠它高亮）。 */
    private fun trackLyricText(info: MediaInfo, now: Long) {
        val lyric = lyricTextOf(info)
        if (lyric.isEmpty() || lyric == currentLyricText) return
        currentLyricText = lyric
        lyricSeenAtMs = now
        val lines = song?.lines ?: return
        if (lines.isEmpty()) return
        textIndex = locateLine(lines, lyric)
    }

    /** 左栏标题栏用的固定歌名（不随歌词变）。 */
    private fun stableTitle(info: MediaInfo): String {
        // 歌名优先：库里存的、歌手行里「歌名 - 歌手」切出来的。
        // 蓝牙车机歌词这类播放器把歌词写进歌曲名行，media.title 每一句都在变，
        // 只有这两个才是固定的歌名。
        val stored = song?.songName?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        if (realSongName.isNotBlank()) return realSongName.trim()
        // 歌词写在歌手 / 专辑行的播放器：标题行本来就是歌名，直接用。
        if (!titleRowMode) return info.title.trim()
        // pendingTitleName 只在从头开始放的时候才会记下（半截插进来的不算）。
        return pendingTitleName.trim()
    }

    private fun lyricTextOf(info: MediaInfo): String {
        if (titleRowMode) {
            val title = info.title.trim()
            if (title.isEmpty()) return ""
            if (realSongName.isNotEmpty() &&
                normalizeTitle(title) == normalizeTitle(realSongName)
            ) {
                return ""
            }
            return title
        }
        val artist = info.artist.trim()
        if (artist.isEmpty()) return ""
        if (looksLikeTitleArtistLine(artist, info.title)) return ""
        if (normalizeTitle(artist) == normalizeTitle(info.title)) return ""
        return artist
    }

    private fun locateLine(lines: List<LyricLine>, text: String): Int? {
        val from = ((textIndex ?: -1).coerceIn(-1, lines.lastIndex)) + 1
        for (i in from until lines.size) {
            if (lines[i].text == text) return i
        }
        for (i in 0 until from) {
            if (lines[i].text == text) return i
        }
        return null
    }

    private fun publish(info: MediaInfo, now: Long) {
        val lines = song?.lines ?: emptyList()
        val stalled = positionStuck(info, now)
        val textIndexNow = if (stalled) textIndex else null

        val index = when (mode) {
            LyricMode.IDLE -> -1
            LyricMode.REPLAY -> textIndexNow ?: lyricIndexAt(lines, info.positionMs)
            LyricMode.CAPTURING -> if (lines.isEmpty()) -1 else lines.lastIndex
        }

        val derived = if (stalled && textIndexNow != null && textIndexNow in lines.indices) {
            lines[textIndexNow].timeMs
        } else {
            null
        }

        state = LyricSessionState(
            media = info,
            mode = mode,
            song = song,
            lines = lines,
            currentIndex = index,
            complete = song?.complete ?: false,
            storedCount = sink.count,
            collecting = mode == LyricMode.CAPTURING || filling,
            derivedPositionMs = derived,
            playingNow = info.playing || now - lyricSeenAtMs <= aliveWindowMs,
            positionStale = stalled,
            songTitle = stableTitle(info),
        )
    }

    companion object {
        private const val MAX_LINE_LENGTH = 80
        private const val NAME_MATCH_DURATION_TOLERANCE_MS = 20_000L
        private const val NAME_MATCH_AMBIGUITY_MS = 5_000L
        private const val NAME_CHECK_WINDOW_MS = 4_000L
        const val POSITION_STALE_AFTER_MS = 6_000L
        const val LYRIC_ALIVE_WINDOW_MS = 5_000L
        private const val FLEETING_SKIP_THRESHOLD_MS = 15_000L

        private fun fmtSeconds(ms: Long): String =
            String.format(Locale.US, "%.1f", ms / 1000.0)
    }
}
