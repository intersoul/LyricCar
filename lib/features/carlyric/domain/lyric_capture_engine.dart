// ============================================================
// 歌词采集引擎
//
// 这是整个 App 的核心：车机不接管音频（声音仍走车机自带蓝牙播放器），
// 只在旁边「偷看」系统媒体会话。播放器每换一句歌词，就会把当前这句写进
// 媒体信息的**歌手行或专辑行**（网易云 / QQ 音乐 / 本项目的哔哩音乐都是
// 这个套路，理由是蓝牙 AVRCP 只肯显示「标题 / 歌手 / 专辑」三行，标题要留给歌名）。
//
// 于是采集就变成了：盯着这两行的变化，变一次记一句 + 记下当时的播放位置。
// 一首歌放完，整首歌词就攒齐了；下次再放同一首，直接用存下来的那份按位置
// 回放，不用重新采集（也就不会「第一遍没歌词」）。
//
// 设计取舍：
//   * 本类**不碰任何平台通道**，只吃 MediaInfo、吐 LyricSessionState，
//     存储通过 LyricSink 注入 —— 这样采集规则可以纯 Dart 单测覆盖。
//   * 会话开始时歌手 / 专辑两行上的值当作**基线**丢掉：那是「真歌手 / 真专辑」，
//     不是歌词。代价是「打开 App 时正在唱的那一句」收不到，从下一句才开始收。
//   * 「同一句连唱两遍」抓不到（只在值**变化**时才记）。这是这类方案的
//     固有缺陷（网易云也一样），换来的是全程不需要任何网络请求。
//   * 重听时新一遍会把已经采过的句子再送一遍，所以并库要按「文本 + 时间」
//     判重（见 `mergeLyricLine`），否则歌词会被整段复制。
// ============================================================
import 'package:flutter/foundation.dart';

import 'lyric_line.dart';
import 'media_info.dart';
import 'song_key.dart';
import 'stored_song.dart';

/// 歌词写在媒体信息的哪一行。不同车机 / 不同 App 不一样，让用户自己试。
enum LyricFieldPreference {
  auto('自动判断'),
  artist('歌手行'),
  album('专辑行'),
  title('歌曲名行');

  const LyricFieldPreference(this.label);

  final String label;

  /// 歌词是不是写在**歌曲名**那一行 —— 这是最特殊的一种，必须单独一套逻辑。
  ///
  /// 网易云 / QQ 音乐的「车载歌词」就是把歌词写进歌曲名行（实测：歌曲名每两秒
  /// 变一句歌词，而歌手行固定是「专辑 - 歌手」）。后果是**歌曲名本身也不可信了**：
  ///   * 不能用它认歌（否则每换一句歌词就变成换了一首歌）；
  ///   * 不能把它当成「真歌名」的基线（这一行里从头到尾就没有歌名）。
  bool get usesTitleRow => this == LyricFieldPreference.title;

  static LyricFieldPreference fromName(String? name) =>
      LyricFieldPreference.values.firstWhere(
        (field) => field.name == name,
        orElse: () => LyricFieldPreference.auto,
      );
}

/// 当前处于哪种状态。
enum LyricMode {
  /// 系统里没有正在播放的媒体会话。
  idle,

  /// 第一次听这首歌：一边听一边攒歌词。
  capturing,

  /// 这首歌存过歌词（哪怕只存了一半）：直接同步回放。
  replay,
}

/// 采集引擎对外的状态快照。
class LyricSessionState {
  const LyricSessionState({
    this.media = MediaInfo.none,
    this.mode = LyricMode.idle,
    this.song,
    this.lines = const [],
    this.currentIndex = -1,
    this.complete = false,
    this.storedCount = 0,
    this.collecting = false,
  });

  final MediaInfo media;
  final LyricMode mode;

  /// 当前这首歌（采集中的 or 回放中的）。
  final StoredSong? song;

  final List<LyricLine> lines;

  /// 当前应该高亮的行号；-1 表示还没到第一句。
  final int currentIndex;

  /// 歌词是否已经采集完整（放完过一整遍）。
  final bool complete;

  /// 歌词库里一共存了多少首歌。
  final int storedCount;

  /// 正在**一边回放一边补词**：这首歌的稿子还没收工（缺开头 / 没放到尾巴），
  /// 所以回放的同时继续采，缺的那几句下次就有了。
  final bool collecting;

  int get lineCount => lines.length;

  bool get hasLyrics => lines.isNotEmpty;

  /// 这首歌是不是从头开始听的。false = 缺开头（见 [StoredSong.fromStart]）。
  bool get fromStart => song?.fromStart ?? false;

  /// 歌词齐了，可以照着回放。
  bool get ready => song?.ready ?? false;

  @override
  bool operator ==(Object other) =>
      other is LyricSessionState &&
      other.mode == mode &&
      other.currentIndex == currentIndex &&
      other.complete == complete &&
      other.storedCount == storedCount &&
      other.collecting == collecting &&
      other.lines.length == lines.length &&
      other.media == media;

  @override
  int get hashCode => Object.hash(
    mode,
    currentIndex,
    complete,
    storedCount,
    collecting,
    lines.length,
    media,
  );
}

/// 采集引擎写歌词的地方（真正的实现是 `data/lyric_store.dart`）。
abstract interface class LyricSink {
  StoredSong? find(String key);

  void put(StoredSong song);

  /// 删掉一条。用于「时长后到、key 变了」时把旧 key 留下的残档清掉。
  bool remove(String key);

  int get count;
}

class LyricCaptureEngine {
  LyricCaptureEngine({
    required this._sink,
    this._field = LyricFieldPreference.auto,
  });

  final LyricSink _sink;
  LyricFieldPreference _field;

  /// 歌词行长度上限，超了多半是被塞了整段文本进来，不像歌词。
  static const int _maxLineLength = 80;

  /// 一首**以前没记过**的歌，只往下走了这么点就切走 → 当它是「随手跳过的」，
  /// 不留记录（详见 [_dropFleetingSong]）。
  ///
  /// 用「播放位置往前走了多少」而不是墙上时钟量「听了多久」：暂停着、App 在后台
  /// 待着的时间都不算听，而且位置本来就是每 250ms 报一次，精度够。
  static const Duration fleetingSkipThreshold = Duration(seconds: 15);

  // ── 当前会话 ──
  /// 用来判断「换歌了」的标识。**不含承载歌词的那一行**（见 [_identityOf]）。
  String _identity = '';

  /// 本次会话里「自动判断」是否**识别出**了歌词写在歌曲名行。
  ///
  /// 不复用用户的设置：这样一首歌一首歌地判，切换播放器（网易云 → 哔哩音乐）
  /// 时不会把上一种判断带到下一首。识别依据见 [_titleLooksLikeLyrics]。
  bool _autoTitleRow = false;
  String _key = '';
  StoredSong? _song;
  LyricMode _mode = LyricMode.idle;
  String _lastArtist = '';
  String _lastAlbum = '';
  String _lastTitle = '';
  bool _primedArtist = false;
  bool _primedAlbum = false;
  bool _primedTitle = false;
  String _lastCapturedText = '';

  /// 这一次盯着这首歌时，是不是从开头就开始的（见 [StoredSong.fromStart]）。
  bool _startedFromStart = false;

  /// 盯着这首歌期间，播放位置的最小值 / 最大值 —— 差值就是「实际听了多久」。
  int _minPositionMs = 0;
  int _maxPositionMs = 0;

  /// 这首歌在我们接手之前，库里就已经有稿子了。
  bool _songHadRecord = false;

  /// 正在**一边回放一边补词**（见 [LyricSessionState.collecting]）。
  ///
  /// 只在这一种情况下为 true：库里已经有一份能回放的稿子，但它还没收工
  /// （缺开头 / 没放到尾巴）。稿子收工后立刻置回 false —— 完整稿子不再回写，
  /// 免得把已经准的时间戳越改越乱。
  ///
  /// 别直接赋值，走 [_syncFilling]：判据只有一条，省得每处改动自己算一遍。
  bool _filling = false;

  /// 定一下「这一遍要不要一边回放一边接着采」。
  ///
  /// 判据只有一条：**这份稿子还没 ready**（不是从头采的，或者没放到过尾巴）。
  /// 中途不再重算 —— 「缺开头」那个标记被抹掉，不等于开头真的采回来了（那要等
  /// [_capture] 收到句子），所以这一遍只认走到尾巴（[_ensureComplete]）才收工。
  void _syncFilling() {
    _filling = _mode == LyricMode.replay && !(_song?.ready ?? false);
  }

  /// 上一次看到的播放位置，用来识别「又从头放了一遍」。
  int _lastPositionMs = 0;

  /// 换歌时报上来的位置可能是僵尸值，标记一下「还没定下来」。
  bool _startUndecided = false;

  LyricSessionState _state = const LyricSessionState();

  LyricSessionState get state => _state;

  void setField(LyricFieldPreference field) => _field = field;

  LyricFieldPreference get field => _field;

  /// 喂一份媒体信息，返回最新状态。UI 侧每次收到原生事件就调一次。
  LyricSessionState handle(MediaInfo info) {
    if (!info.hasTrack) {
      _reset();
      _publish(info);
      return _state;
    }

    _maybeAdoptTitleRow(info);

    final songChange =
        _identity.isNotEmpty &&
        (_identityOf(info) != _identity || _startsNewSong(info));
    if (_identityOf(info) != _identity || _startsNewSong(info)) {
      _start(info, songChange: songChange);
    } else {
      _refineKey(info);
      // 换歌时那份位置可能是僵尸值，等它掉回开头再定「是不是从头听的」
      _resolveStartUndecided(info);
      _detectReplay(info);
    }

    if (_mode == LyricMode.capturing || _filling) {
      _capture(info);
      _maybeComplete(info);
    }

    _lastArtist = info.artist;
    _lastAlbum = info.album;
    _lastTitle = info.title;
    final positionMs = info.position.inMilliseconds;
    _lastPositionMs = positionMs;
    // 记下这首歌「实际听了多久」的上下界（判「随手跳过的」用的就是它）
    if (positionMs < _minPositionMs) _minPositionMs = positionMs;
    if (positionMs > _maxPositionMs) _maxPositionMs = positionMs;

    _publish(info);
    return _state;
  }

  /// 「自动判断」模式下，认一认歌词是不是写在**歌曲名行**。
  ///
  /// 判据只认一条，但很硬：**标题变了，而播放位置没有往回退**。
  ///   * 歌词：同一首歌里一句接一句，位置一路向前（网易云实测每两三秒一句）；
  ///   * 换歌：标题一变，位置**从头开始**——那是一次很大的回退。
  ///
  /// 容差 0.75 秒是留给原生侧位置外推的抖动的：车机蓝牙协议栈大约每秒重发一次
  /// 播放状态，重发时带的位置可能比上一次推算出来的值略小。实测那次是 181006ms →
  /// 180943ms，往回退了 63ms —— 第一版写成「位置必须前进 0.5 秒」就正好被这点抖动
  /// 挡在门外，一直认不出来（真机上抓到的）。
  ///
  /// 这样用户不用为每个播放器手动切设置 —— 同一个车机上换着放哔哩音乐
  /// （歌词在歌手行）和网易云（歌词在歌曲名行）也都能各自认出来。
  void _maybeAdoptTitleRow(MediaInfo info) {
    if (_field != LyricFieldPreference.auto) return;
    if (_autoTitleRow) return;
    if (_lastTitle.isEmpty) return;
    if (normalizeTitle(info.title) == normalizeTitle(_lastTitle)) return;
    // 位置明显往回退了 = 换了一首歌，不是歌词
    if (info.position.inMilliseconds + 750 < _lastPositionMs) return;
    // 时长变了也说明换歌（同专辑下一首，歌手/专辑都一样）
    final previous = _song?.duration.inSeconds ?? 0;
    if (previous > 0 && info.duration.inSeconds > 0) {
      if ((info.duration.inSeconds - previous).abs() > 2) return;
    }

    _autoTitleRow = true;
    // 身份和 key 都得按「歌词在歌曲名行」重算，并把已采的句子搬过去
    _identity = _identityOf(info);
    _key = _keyOf(info);
    final stored = _sink.find(_key);
    // ⚠️ 认到新 key 上的稿子时，**整套标记都要留着**（尤其 complete / fromStart）：
    // 以前这里重新 new 一个 StoredSong，把 complete 掉了，一份已经采齐的稿子会被
    // 当成「还没收工」而重新开采。
    final base =
        stored ??
        StoredSong(
          key: _key,
          title: _displayTitle(info),
          duration: info.duration,
          fromStart: _startedFromStart,
          lines: List.of(_song?.lines ?? const <LyricLine>[]),
        );
    _song = base.copyWith(
      key: _key,
      title: _displayTitle(info),
      duration: info.duration.inSeconds > 0 ? info.duration : base.duration,
      fromStart: base.fromStart || _startedFromStart,
      lines: List.of(base.lines),
    );
    _lastCapturedText = base.lines.isEmpty ? '' : base.lines.last.text;
    // 这一行里没有「真歌名」可丢，第一眼看到的就是歌词
    _primedTitle = true;
    _mode = base.replayable ? LyricMode.replay : LyricMode.capturing;
    _syncFilling();
    debugPrint(
      '[LyricCar] 识别出歌词写在「歌曲名行」（标题在变、播放位置没回到开头），'
      '已自动切换并改用「歌手 + 专辑 + 时长」认歌',
    );
  }

  /// 换歌时那份「播放位置」到底可不可信 —— 用「它有没有掉回开头」来判。
  ///
  /// 实测（BYD 车机 + 网易云）：换歌那一刻报上来的位置带着上一首的进度（抓到过
  /// 5.5s、10.5s），过几拍才跟着新歌掉回 0 附近。如果当场就按 10.5s 判「不是从头
  /// 听起」，这首歌会被误标成「缺开头」，明明从头听到了，却要再放一遍才认。
  ///
  /// 所以：换歌时位置偏大 → 先挂着。掉回开头 = 这次确实从头听起（顺手把标记改对）；
  /// 一路往前跑过 20 秒 = 真是从半路插进来的，保持「缺开头」。
  void _resolveStartUndecided(MediaInfo info) {
    if (!_startUndecided) return;
    final ms = info.position.inMilliseconds;
    if (ms > 20000) {
      _startUndecided = false;
      return;
    }
    if (ms > 1000) return;

    _startUndecided = false;
    _startedFromStart = true;
    final song = _song;
    if (song == null || song.fromStart) return;
    final patched = song.copyWith(fromStart: true);
    _song = patched;
    _sink.put(patched);
    debugPrint('[LyricCar] 位置掉回开头 → 这首其实是从头听起的，改标记');
  }

  /// 一首歌放完又从头放了一遍（单曲循环 / 手动重播）。
  ///
  /// 标题没变，所以 `_start` 不会被调用 —— 只能靠「位置从后半段突然跳回开头」认出来。
  /// 认出来之后：只要库里已经有一份能回放的稿子（哪怕只采了一半），就**从头照着它
  /// 滚歌词**，同时继续补缺的那些句；一句都还没存过才纯采集。
  ///
  /// 注意这里**不**主动标「完整」：真正的「放完了」由 `_maybeComplete`（位置到尾巴）
  /// 判断，重播本身只能说明「回到了开头」，说明不了整首放完。
  /// 唯一的例外是时长未知 —— 那种情况下播放器永远不会报「到尾巴了」，
  /// 只能把「又从头放了」当作收工信号。
  void _detectReplay(MediaInfo info) {
    if (_mode == LyricMode.idle) return;
    final ms = info.position.inMilliseconds;
    if (_lastPositionMs <= 0) return;
    if (ms > 5000 || _lastPositionMs < 30000) return;
    debugPrint(
      '[LyricCar] 位置跳回开头（${(_lastPositionMs / 1000).round()}s → '
      '${(ms / 1000).toStringAsFixed(1)}s）：这首又从头放了一遍',
    );

    if (info.duration.inMilliseconds <= 0) _ensureComplete();

    final song = _song;
    // 有稿子（哪怕只采了一半）就这一遍照样回放 —— 第二遍立刻能看到同步歌词，
    // 没收工的话继续补，回放和补采并不冲突
    _mode = (song != null && song.replayable)
        ? LyricMode.replay
        : LyricMode.capturing;
    // ⚠️ 必须在下面抹「缺开头」之前算：这个标记一抹，稿子看起来就 ready 了，
    // 可开头其实还没采回来，这一遍该采的还得采。
    _syncFilling();

    // 「又从头放了」本身就是「我们现在从开头开始听了」的证据 ——
    // 上一次多半是从中间插进来的，这一遍正好把开头补上。
    _startedFromStart = true;
    final tracking = _song;
    if (tracking != null && !tracking.fromStart) {
      final patched = tracking.copyWith(fromStart: true);
      _song = patched;
      _sink.put(patched);
    }

    // 接着采的话基线要重置：新一遍开头这两行写的仍然是「真歌手 / 真专辑」
    _primedArtist = false;
    _primedAlbum = false;
    _lastArtist = '';
    _lastAlbum = '';
    // 注意**不要**清 _lastCapturedText：这一遍开头报上来的还是上次那句，清了就会
    // 把同一句重复收进库（时间差得多，mergeLyricLine 的窗口拦不住）。
  }

  // ----------------------------------------------------------
  // 换歌 / 起会话
  // ----------------------------------------------------------
  /// 歌词是否写在歌曲名行 —— 用户显式选的，或者「自动判断」当场识别出来的。
  bool get _titleRowMode => _field.usesTitleRow || _autoTitleRow;

  /// 用来判断「换歌了」的标识 —— **必须排除承载歌词的那一行**。
  ///
  /// 歌曲名行模式下歌词就在标题里，标题每两秒变一次，拿它认歌等于每句歌词都
  /// 换了一首歌。这种播放器会把「这是哪首」挪到歌手行（实测网易云/QQ 的格式是
  /// `专辑 - 歌手`），所以改用「歌手 + 专辑」认歌。
  String _identityOf(MediaInfo info) {
    if (_titleRowMode) {
      return '${normalizeTitle(info.artist)}\u0000${normalizeTitle(info.album)}';
    }
    return normalizeTitle(info.title);
  }

  /// 这首歌存进歌词库时用的 key。
  ///
  /// 歌手行 / 专辑行模式沿用「标题 + 时长」——**格式不能动**，否则用户车上
  /// 已经采好的歌词会全部对不上号。歌曲名行模式只能用「歌手 + 专辑 + 时长」。
  String _keyOf(MediaInfo info) {
    if (_titleRowMode) {
      return titleRowSongKey(
        artist: info.artist,
        album: info.album,
        duration: info.duration,
      );
    }
    return songKey(title: info.title, duration: info.duration);
  }

  /// 歌词库里那条记录显示什么名字。
  ///
  /// 歌曲名行模式下**真歌名是拿不到的**（那一行被歌词占了，而元数据里没有
  /// 第二个地方藏着它 —— 实测只有 7 个 AVRCP 键）。这时退而用播放器挪到歌手行
  /// 的身份信息（`专辑 - 歌手`），至少能在库里认出是哪张专辑哪个人。
  String _displayTitle(MediaInfo info) {
    if (!_titleRowMode) return info.title.trim();
    final artist = info.artist.trim();
    if (artist.isNotEmpty) return artist;
    return info.album.trim();
  }

  /// 歌手 / 专辑都没变，但**时长变了** —— 歌曲名行模式下这是换歌的信号：
  /// 同一张专辑连着放两首，歌手和专辑都一样，只有时长不同。
  ///
  /// 必须跟「时长后到」区分开：那种是之前还不知道时长（0），属于同一首歌。
  bool _startsNewSong(MediaInfo info) {
    if (!_titleRowMode || _mode == LyricMode.idle) return false;
    final previous = _song?.duration.inSeconds ?? 0;
    final now = info.duration.inSeconds;
    if (previous <= 0 || now <= 0) return false;
    return (now - previous).abs() > 2;
  }

  void _start(MediaInfo info, {bool songChange = false}) {
    // 上一首该不该留下记录，在真正换掉之前先判掉
    _dropFleetingSong();

    // 每首歌重新认一次「歌词写在哪一行」，这样中途换播放器也不会带着旧判断
    _autoTitleRow = false;
    _identity = _identityOf(info);
    _song = null;
    _lastCapturedText = '';
    _key = _keyOf(info);
    debugPrint(
      '[LyricCar] ${songChange ? "换歌" : "起会话"}：key=$_key 位置='
      '${(info.position.inMilliseconds / 1000).toStringAsFixed(1)}s',
    );

    // 起播时这两行上是「真歌手 / 真专辑」，不是歌词 —— 记成基线，之后的变化才算歌词。
    // 值还空着（有的播放器先只给标题）时不打基线标记：下一个非空值才是真歌手。
    _lastArtist = info.artist;
    _lastAlbum = info.album;
    _primedArtist = info.artist.trim().isNotEmpty;
    _primedAlbum = info.album.trim().isNotEmpty;
    // 新会话没有「上一句歌词」，所以从空开始 —— 歌曲名行模式要立刻收下第一眼
    // 看到的那句（那一行里从头到尾都是歌词，没有「真歌名」这种基线可丢）。
    _lastTitle = '';
    _primedTitle = _titleRowMode;

    // 第一眼看到这首歌时位置还在开头，才算「从头听起」。
    // 否则（重装 / 中途打开时这首歌已经唱了一半）会记下「缺开头」，
    // 后面即使放到了尾巴也不标记完整 —— 免得拿一份缺开头的歌词回放一辈子。
    _startedFromStart = info.position.inMilliseconds <= kFromStartToleranceMs;
    // 换歌那一刻的位置很可能是上一首留下的僵尸值（蓝牙协议栈晚一拍才把新歌的
    // 播放状态发过来）。这时先不下「缺开头」的结论，等它掉回开头 —— 见
    // [_resolveStartUndecided]。App 自己刚起会话时不适用（那确实是半路插进来的）。
    _startUndecided = songChange && !_startedFromStart;

    _minPositionMs = info.position.inMilliseconds;
    _maxPositionMs = info.position.inMilliseconds;
    final stored = _sink.find(_key);
    _songHadRecord = stored != null;
    if (stored != null && stored.replayable) {
      // 库里已经有稿子（哪怕只采了一半）→ 直接照着它回放。
      //
      // 这正是「中途退出再进来只会显示最后一句」的解法：以前只认收工了的稿子
      // （complete && fromStart），半份稿子只能继续采集、界面钉在最后一句上。
      // 现在半份稿子也按时间戳滚，一边滚一边把缺的补上（[_filling]）。
      _song = stored;
      _mode = LyricMode.replay;
      _syncFilling();
      _lastCapturedText = stored.lines.last.text;
      return;
    }

    // 没存过（或只存了一句，多半是把真歌手误当歌词了）→ 从头采
    final seed = stored?.lines ?? const <LyricLine>[];
    _song = StoredSong(
      key: _key,
      title: _displayTitle(info),
      artist: stored?.artist ?? '',
      album: stored?.album ?? '',
      duration: info.duration,
      source: stored?.source ?? LyricSource.captured,
      fromStart: (stored?.fromStart ?? false) || _startedFromStart,
      lines: List.of(seed),
    );
    _lastCapturedText = seed.isEmpty ? '' : seed.last.text;
    _mode = LyricMode.capturing;
    _syncFilling();
  }

  /// 时长常常是后到的（先给标题歌手、过一会儿才给 duration）。
  /// 时长一变，key 就变，要把已采的歌词搬到新 key 下面。
  void _refineKey(MediaInfo info) {
    if (_mode != LyricMode.capturing) return;
    if (info.duration.inSeconds <= 0) return;
    // 「歌词写在歌曲名行」时，时长**已知之后**又变，是换歌（见 _startsNewSong），
    // 那种情况绝不能把上一首的歌词搬过来 —— 只有「之前还不知道时长」才搬。
    if (_titleRowMode && (_song?.duration.inSeconds ?? 0) > 0) return;

    final key = _keyOf(info);
    if (key == _key) return;

    final stored = _sink.find(key);
    if (stored != null && stored.replayable) {
      _key = key;
      _song = stored;
      _mode = LyricMode.replay;
      _syncFilling();
      _lastCapturedText = stored.lines.last.text;
      return;
    }

    final previousKey = _key;
    final song =
        (_song ??
                StoredSong(
                  key: key,
                  title: _displayTitle(info),
                  lines: const [],
                ))
            .copyWith(key: key, duration: info.duration);
    _song = song;
    _key = key;
    // 旧 key（多半是「时长还不知道」时的 xx|0）留着就是歌词库里的一条垃圾
    if (previousKey != key) _sink.remove(previousKey);
    // 一句都没采到就别往库里写。切歌快的时候（尤其「歌词写在歌曲名行」但设置
    // 选错了的情况）会在库里堆出一串空壳条目，什么用都没有。
    if (song.lines.isNotEmpty) _sink.put(song);
  }

  /// 随手跳过的歌不留记录。
  ///
  /// 用户经常听几秒就切下一首（试听、找歌）。这种歌既没采到几句，下次也不会
  /// 再听，留在歌词库里只是垃圾：所以**以前没记过的歌**、盯着它不到
  /// [fleetingSkipThreshold] 就被切走（或会话断开）→ 直接删掉。
  ///
  /// 三种情况不删：
  ///   * 库里本来就有稿子（可能已经采齐了，用户只是不想听了）—— 回放中的歌
  ///     属于这一种，因为只有存过且齐了的稿子才进回放；
  ///   * 盯着它不到 [fleetingSkipThreshold] 就被切走（会话断开也算）；
  ///   * 听够了时长。
  void _dropFleetingSong() {
    final song = _song;
    final hadRecord = _songHadRecord;
    final listenedMs = _maxPositionMs - _minPositionMs;
    _song = null;
    _songHadRecord = false;
    _minPositionMs = 0;
    _maxPositionMs = 0;

    if (song == null || hadRecord) return;
    if (listenedMs >= fleetingSkipThreshold.inMilliseconds) return;
    if (_sink.remove(song.key)) {
      // 真的删掉了才吭声
      debugPrint(
        '[LyricCar] 《${song.title}》只听了 ${(listenedMs / 1000).round()} 秒就切走了，不留记录',
      );
    }
  }

  void _reset() {
    _dropFleetingSong();
    _identity = '';
    _key = '';
    _song = null;
    _mode = LyricMode.idle;
    _lastArtist = '';
    _lastAlbum = '';
    _lastTitle = '';
    _primedArtist = false;
    _primedAlbum = false;
    _primedTitle = false;
    _lastCapturedText = '';
    _lastPositionMs = 0;
    _startedFromStart = false;
    _startUndecided = false;
    _autoTitleRow = false;
    _songHadRecord = false;
    _filling = false;
    _minPositionMs = 0;
    _maxPositionMs = 0;
  }

  // ----------------------------------------------------------
  // 采集
  // ----------------------------------------------------------
  void _capture(MediaInfo info) {
    final picked = _pickCandidate(info);
    if (picked == null) return;

    final text = picked.text;
    final primed = switch (picked.field) {
      LyricFieldPreference.artist => _primedArtist,
      LyricFieldPreference.album => _primedAlbum,
      LyricFieldPreference.title => _primedTitle,
      LyricFieldPreference.auto => true,
    };

    if (!primed) {
      switch (picked.field) {
        case LyricFieldPreference.artist:
          _primedArtist = true;
        case LyricFieldPreference.album:
          _primedAlbum = true;
        case LyricFieldPreference.title:
          _primedTitle = true;
        case LyricFieldPreference.auto:
          break;
      }
      // 这一行第一次出现非空值 —— 那是「真歌手 / 真专辑」，不是歌词。
      // 代价是：如果打开 App 时歌词已经在滚，当场那一句会丢掉，从下一句才开始收。
      return;
    }

    if (text == _lastCapturedText) return;
    if (text.length > _maxLineLength) return;
    // 「歌曲名 - 歌手」这种身份信息混在歌词行里（网易云把歌词写在歌曲名行，
    // 身份信息就挪到了歌手行），换歌时会变一次 —— 采进来就是垃圾句子。
    if (picked.field != LyricFieldPreference.title &&
        looksLikeTitleArtistLine(text, info.title)) {
      return;
    }
    // 标题被错写进歌词行的情况，忽略。
    // ⚠️ 歌曲名行模式下 text 本来就是标题，这个比较恒真 —— 必须跳过，
    // 否则那句话永远进不来（踩过）。
    if (!_titleRowMode && normalizeTitle(text) == normalizeTitle(info.title)) {
      return;
    }

    final base =
        _song ??
        StoredSong(
          key: _key,
          title: _displayTitle(info),
          fromStart: _startedFromStart,
          lines: const [],
        );
    final lines = mergeLyricLine(
      base.lines,
      LyricLine(timeMs: info.position.inMilliseconds, text: text),
    );
    final song = base.copyWith(
      title: _displayTitle(info),
      duration: info.duration.inSeconds > 0 ? info.duration : base.duration,
      // 这一次是从头听的，就把「缺开头」的标记抹掉
      fromStart: base.fromStart || _startedFromStart,
      lines: lines,
    );
    _song = song;
    _lastCapturedText = text;
    _sink.put(song);
    // 判重命中时 mergeLyricLine 原样返回，那就**没有新句子** —— 日志别报「+第 N 句」。
    // 回放半份稿子时新一遍会把老句子再送一遍，这条日志是现场唯一的证据，
    // 报假账会让人以为还在不停采到新词。
    if (!identical(lines, base.lines)) {
      debugPrint(
        '[LyricCar] +第 ${lines.length} 句 '
        '${(info.position.inMilliseconds / 1000).toStringAsFixed(1)}s：$text',
      );
    }
  }

  /// 判断这一轮哪一行是歌词。返回 null 表示这轮没有新歌词。
  ({LyricFieldPreference field, String text})? _pickCandidate(MediaInfo info) {
    final artist = info.artist.trim();
    final album = info.album.trim();
    final title = info.title.trim();
    final artistChanged = info.artist != _lastArtist;
    final albumChanged = info.album != _lastAlbum;
    final titleChanged = info.title != _lastTitle;

    // 生效在歌曲名行时只认标题那一行（显式选的，或自动识别出来的）
    if (_titleRowMode) {
      if (titleChanged && title.isNotEmpty) {
        return (field: LyricFieldPreference.title, text: title);
      }
      return null;
    }

    switch (_field) {
      case LyricFieldPreference.title:
        if (titleChanged && title.isNotEmpty) {
          return (field: LyricFieldPreference.title, text: title);
        }
      case LyricFieldPreference.artist:
        if (artistChanged && artist.isNotEmpty) {
          return (field: LyricFieldPreference.artist, text: artist);
        }
      case LyricFieldPreference.album:
        if (albumChanged && album.isNotEmpty) {
          return (field: LyricFieldPreference.album, text: album);
        }
      case LyricFieldPreference.auto:
        // ⚠️ auto **故意不认歌曲名行**：标题变了既可能是歌词，也可能是同专辑的
        // 下一首（歌手 / 专辑都不变），光看元数据分不出来。所以歌曲名行必须由
        // 用户显式指定（网易云 / QQ 音乐的用户去设置里选「歌曲名行」）。
        if (artistChanged && albumChanged) {
          // 两行同时改成同一句 → 有的播放器两行都写，这就是歌词
          if (artist.isNotEmpty && artist == album) {
            return (field: LyricFieldPreference.artist, text: artist);
          }
          // 两行同时改而且内容不同 → 多半是换歌时在写「真歌手 + 真专辑」，不敢认
          return null;
        }
        if (artistChanged && artist.isNotEmpty) {
          return (field: LyricFieldPreference.artist, text: artist);
        }
        if (albumChanged && album.isNotEmpty) {
          return (field: LyricFieldPreference.album, text: album);
        }
    }
    return null;
  }

  /// 放到尾巴上就算这首歌「整首放完了」，歌词收工，下一遍直接回放。
  void _maybeComplete(MediaInfo info) {
    final total = info.duration.inMilliseconds;
    if (total <= 0) return;
    if (info.position.inMilliseconds < total - 2000) return;
    _ensureComplete();
  }

  /// 把当前采到的东西定稿。
  ///
  /// 三个条件缺一不可：
  ///   * 采到两句以上 —— 只抓到一句，多半是把开头的「真歌手」误当歌词了；
  ///   * 从头听的（`fromStart`）—— 否则这份稿子缺了开头，标成完整就再也补不回来；
  ///   * 已经在两行里看到过变化（`usable` 已覆盖）。
  /// 不满足就不标完整，下次从头放一遍会把缺的补上（并库按文本 + 时间判重）。
  void _ensureComplete() {
    final song = _song;
    if (song == null || song.complete || !song.usable) return;
    if (!song.fromStart) return;
    final done = song.copyWith(
      complete: true,
      updatedAtMs: DateTime.now().millisecondsSinceEpoch,
    );
    _song = done;
    _sink.put(done);
    // 收工了就不再回写：这份稿子已经是最终版，再采只会把准的时间戳越改越乱
    _filling = false;
  }

  void _publish(MediaInfo info) {
    final lines = _song?.lines ?? const <LyricLine>[];
    final index = switch (_mode) {
      LyricMode.idle => -1,
      // 回放：按播放位置高亮（半份稿子也一样 —— 前面的句子照滚，
      // 还没采到的位置返回 -1，界面就不会乱指）
      LyricMode.replay => lyricIndexAt(lines, info.position),
      // 采集：最新抓到的那句就是「当前句」
      LyricMode.capturing => lines.isEmpty ? -1 : lines.length - 1,
    };

    _state = LyricSessionState(
      media: info,
      mode: _mode,
      song: _song,
      lines: lines,
      currentIndex: index,
      complete: _song?.complete ?? false,
      storedCount: _sink.count,
      collecting: _mode == LyricMode.capturing || _filling,
    );
  }
}
