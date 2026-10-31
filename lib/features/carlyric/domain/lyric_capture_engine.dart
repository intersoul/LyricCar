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
    this.derivedPosition,
    this.playingNow = false,
    this.positionStale = false,
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

  /// 播放器报的播放位置**不可信**时（卡住不动、还卡在时长上），用歌词自己的时间戳
  /// 算出来的位置。null = 报的位置能用。
  ///
  /// 真机上遇到过：车机蓝牙协议栈停止更新播放状态（`state=PAUSED`、位置永远等于
  /// 时长），但媒体信息里的歌词一句句在变。这时候按报的位置算，进度条永远是满的、
  /// 时间两边都是同一个数、高亮永远停在最后一句。
  final Duration? derivedPosition;

  /// 界面该认的「正在播放」：播放器说在放，**或者**歌词还在往前推进。
  ///
  /// 协议栈把状态报成「暂停」时（真机遇到过，见 [derivedPosition]），歌词却一句句
  /// 在变 —— 那种情况按「在放」显示，播放/暂停按钮才不会反过来。
  final bool playingNow;

  /// 报的播放位置是不是卡住了（详见 [derivedPosition]）。
  final bool positionStale;

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
      other.derivedPosition == derivedPosition &&
      other.playingNow == playingNow &&
      other.positionStale == positionStale &&
      other.lines.length == lines.length &&
      other.media == media;

  @override
  int get hashCode => Object.hash(
    mode,
    currentIndex,
    complete,
    storedCount,
    collecting,
    derivedPosition,
    playingNow,
    positionStale,
    lines.length,
    media,
  );
}

/// 采集引擎写歌词的地方（真正的实现是 `data/lyric_store.dart`）。
abstract interface class LyricSink {
  StoredSong? find(String key);

  /// 库里所有稿子（按歌名找歌词时要扫一遍 —— 几百首，扫一次不到 1ms）。
  Iterable<StoredSong> get all;

  void put(StoredSong song);

  /// 删掉一条。用于「时长后到、key 变了」时把旧 key 留下的残档清掉。
  bool remove(String key);

  int get count;
}

class LyricCaptureEngine {
  LyricCaptureEngine({
    required this._sink,
    this._field = LyricFieldPreference.auto,
    DateTime Function()? clock,
    this.staleAfter = positionStaleAfter,
    this.aliveWindow = lyricAliveWindow,
  }) : _clock = clock ?? DateTime.now;

  final LyricSink _sink;
  LyricFieldPreference _field;

  /// 取当前时间。测试里换成假时钟，「6 秒没动」「5 秒内来过歌词」这种判断才能
  /// 不用真的等。
  final DateTime Function() _clock;

  /// 多久没动算「位置卡住」（测试里传更短的值）。
  final Duration staleAfter;

  /// 多久之内来过歌词算「还在放」。
  final Duration aliveWindow;

  int _nowMs() => _clock().millisecondsSinceEpoch;

  /// 歌词行长度上限，超了多半是被塞了整段文本进来，不像歌词。
  static const int _maxLineLength = 80;

  /// 重名时按「时长最接近」挑那首；差超过这个值就当不是同一首。
  ///
  /// 同一首歌不同版本（专辑版 / 现场版）时长能差十几秒，所以给得松一点；
  /// 真正的重名（两首不同的歌同名）时长通常差得多。
  static const Duration nameMatchDurationTolerance = Duration(seconds: 20);

  /// 重名的两首都「时长很接近」时，分不清是哪一首 —— 宁可走回退路径。
  static const Duration nameMatchAmbiguity = Duration(seconds: 5);

  /// 验「名字匹配到的稿子对不对」时，同一位置前后多少毫秒内的句子算「附近」。
  static const int nameCheckWindowMs = 4000;

  /// 播放器报的播放位置多久没变过，就当它「卡住了」（不可信）。
  ///
  /// 真机遇到过：车机蓝牙协议栈停了播放状态推送，位置永远等于一个固定值（有时正好
  /// 是时长），而媒体信息里的歌词还在按两秒一句地变。这时候按位置算的进度条永远是
  /// 满的、时间左右两边一样、高亮永远停在最后一句 —— 车主截图反馈的就是这个。
  static const Duration positionStaleAfter = Duration(seconds: 6);

  /// 多久之内收到过新歌词，就算「还在放」。
  static const Duration lyricAliveWindow = Duration(seconds: 5);

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

  /// 播放器最后报的播放位置变过的时间（墙上时钟），用来判「位置卡住了」。
  int _positionChangedAtMs = 0;

  /// 最近一次看到**新的一句歌词**的时间（墙上时钟）。
  int _lyricSeenAtMs = 0;

  /// 最近一句歌词的文本，用来判断「换了一句」。
  String _currentLyricText = '';

  /// 按文本在稿子里认出来的当前句（位置不可信时拿它当当前句）。
  int? _textIndex;

  /// 「位置卡住了」这件事只吭一声，别每句都刷日志。
  bool _loggedStuck = false;

  /// 这一遍认出来的**真歌名**（见 [StoredSong.matchName]）。空 = 认不出来。
  String _realSongName = '';

  /// 切歌后第一眼看到的标题 —— 歌手行不是 `歌曲名 - 歌手` 时，它往往还是真歌名
  /// （歌词过一会儿才盖上来）。等第一次变化时再确认，见 [_capture]。
  String _pendingTitleName = '';

  /// 当前这份稿子是**按歌名**匹配到的（而不是按「换歌后那套 key」）。
  bool _nameMatched = false;

  /// 名字匹配已经用歌词验证过了，后面不再逐句验。
  bool _nameMatchConfirmed = false;

  /// 验证时「对不上」的次数，够两次就否定这次匹配。
  int _nameConflicts = 0;

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
      _publish(info, _nowMs());
      return _state;
    }

    final nowMs = _nowMs();
    // 位置变没变过 —— 判「播放器还报不报位置」就看它
    if (info.position.inMilliseconds != _lastPositionMs) {
      _positionChangedAtMs = nowMs;
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

    // 记下「刚才那句歌词」（回放时靠它在稿子里定位当前句）。
    // ⚠️ 必须排在 _capture 前面：_capture 用「歌词刚来过」判断播放位置是不是卡住了。
    _trackLyricText(info, nowMs);

    // 回放时也要进 _capture 的情况：正在补词（_filling），或者名字匹配到的那份
    // 稿子还没验证过（要用歌词本身确认它真的是这首歌）。
    final verifying = _nameMatched && !_nameMatchConfirmed;
    if (_mode == LyricMode.capturing || _filling || verifying) {
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

    _publish(info, nowMs);
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
    // 切过来的时候歌曲名行早就被歌词占了，只能从歌手行拿真歌名
    _realSongName = songNameFromTitleArtistLine(info.artist) ?? '';
    _song = base.copyWith(
      key: _key,
      title: _displayTitle(info),
      songName: _realSongName.isNotEmpty ? _realSongName : base.songName,
      duration: info.duration.inSeconds > 0 ? info.duration : base.duration,
      fromStart: base.fromStart || _startedFromStart,
      lines: List.of(base.lines),
    );
    _lastCapturedText = base.lines.isEmpty ? '' : base.lines.last.text;
    // 这一行里没有「真歌名」可丢，第一眼看到的就是歌词
    _primedTitle = true;
    _mode = base.replayable ? LyricMode.replay : LyricMode.capturing;
    _syncFilling();

    // 认出来这一行是歌词之后，才谈得上「按歌名找」。这一步很关键：auto 模式
    // 第一眼拿不到歌名（那时标题可能已经是歌词了），只能等认出来的这一刻。
    final byName = _matchByName(info);
    if (byName != null && byName.key != _key) {
      _song = byName;
      _key = byName.key;
      _mode = LyricMode.replay;
      _nameMatched = true;
      _nameMatchConfirmed = false;
      _nameConflicts = 0;
      _syncFilling();
      _lastCapturedText = '';
      debugPrint('[LyricCar] 按歌名匹配到《${byName.displayName}》→ 直接回放');
      return;
    }
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

  /// 这一眼能拿到的真歌名。
  ///
  /// 只看**歌手行**：歌词写在歌曲名行时，身份信息被挪到了歌手行
  /// （`歌曲名 - 歌手`），这是唯一还能拿到歌名的地方。
  ///   * 「自动判断」还没认出歌词在哪一行时返回空 —— 那时候标题可能已经是歌词了，
  ///     等 [_maybeAdoptTitleRow] 认出来再补（见那里）；
  ///   * 显式指定歌手行 / 专辑行时，标题一直是歌名，直接用。
  String _realNameOf(MediaInfo info) {
    final fromArtist = songNameFromTitleArtistLine(info.artist);
    if (fromArtist != null) return fromArtist;
    if (_field == LyricFieldPreference.auto || _titleRowMode) return '';
    return info.title.trim();
  }

  /// 按**真歌名**在库里找一份能回放的稿子；找不到或有歧义返回 null。
  ///
  /// 为什么优先按歌名：歌名在绝大多数情况下是唯一的，而「歌手 + 专辑 + 时长」
  /// 那套 key 里带着播放器报的时长 —— 同一首歌报 `3125` 和 `3120` 秒就会变成两个
  /// key，白采一遍（真机抓到过）。歌名不受这个影响。
  ///
  /// 重名（少数情况）就按时长挑最近的；两首都一样近就认输返回 null，
  /// 让调用方退回 key 匹配 —— 宁可多采一遍，也不能把别人的歌词扣在这首歌头上。
  StoredSong? _matchByName(MediaInfo info) {
    final name = _realSongName.trim();
    if (name.isEmpty) return null;
    final target = normalizeTitle(name);

    final durationMs = info.duration.inMilliseconds;
    final scored = <StoredSong>[];
    for (final song in _sink.all) {
      if (!song.replayable) continue;
      final nameHit =
          normalizeTitle(song.matchName) == target ||
          normalizeTitle(song.title) == target;
      if (!nameHit) continue;
      // 名字对上、时长也要对得上才算同一首：歌手行如果是 `专辑 - 歌手`（而不是
      // `歌曲名 - 歌手`），同一张专辑里的几首歌名字会看起来一模一样，只能靠时长分。
      // 时长未知（0）的那份也收下 —— 采集时常常先给标题、后给时长。
      final own = song.duration.inMilliseconds;
      if (own > 0 &&
          durationMs > 0 &&
          (own - durationMs).abs() >
              nameMatchDurationTolerance.inMilliseconds) {
        continue;
      }
      scored.add(song);
    }
    if (scored.isEmpty) return null;
    if (scored.length == 1) return scored.single;

    // 重名：按时长挑。时长为 0（还不知道）的排在最后。
    if (durationMs <= 0) return null;
    scored.sort(
      (a, b) => (a.duration.inMilliseconds - durationMs).abs().compareTo(
        (b.duration.inMilliseconds - durationMs).abs(),
      ),
    );
    if (scored.length >= 2) {
      final gap =
          (scored[1].duration.inMilliseconds -
                  scored[0].duration.inMilliseconds)
              .abs();
      if (gap <= nameMatchAmbiguity.inMilliseconds) return null;
    }
    return scored.first;
  }

  /// 歌名匹配到的稿子到底对不对 —— 用**歌词本身**来验。
  ///
  /// 歌名重名、或者播放器报的歌名和库里那份不是同一首时，光看名字会张冠李戴。
  /// 判据很简单：这一句歌词应当落在稿子里**同一位置附近**的那一句上。
  ///   * 位置附近有句子、但文本不一样 → 对不上；
  ///   * 位置落在稿子覆盖的范围内、附近却一句都没有 → 也对不上（稿子是连续的）；
  ///   * 位置在稿子覆盖范围之外（开头没采到 / 后面还没采到）→ 不算对不上。
  bool _conflictsWithSong(StoredSong song, int positionMs, String text) {
    final lines = song.lines;
    if (lines.length < 2) return false;
    var near = 0;
    var sameText = false;
    for (final line in lines) {
      if ((line.timeMs - positionMs).abs() > nameCheckWindowMs) continue;
      near++;
      if (line.text == text) sameText = true;
    }
    if (near > 0) return !sameText;
    return positionMs >= lines.first.timeMs - 2000 &&
        positionMs <= lines.last.timeMs + 2000;
  }

  /// 验证名字匹配。返回 false = 这一句先别写进稿子。
  ///
  /// 连续两次对不上就否定这次匹配：回退到「换歌后那套 key」再找一遍，
  /// 找不到就新开一份（错的那份原样留着，不动它）。
  bool _verifyNameMatch(MediaInfo info, String text) {
    final song = _song;
    if (song == null) return true;
    if (!_conflictsWithSong(song, info.position.inMilliseconds, text)) {
      _nameMatchConfirmed = true;
      return true;
    }
    _nameConflicts++;
    debugPrint('[LyricCar] 名字匹配对不上（第 $_nameConflicts 次）：$text');
    if (_nameConflicts < 2) return false;
    _rejectNameMatch(info);
    return false;
  }

  /// 名字匹配不可信 → 改用「换歌后那套 key」。
  void _rejectNameMatch(MediaInfo info) {
    _nameMatched = false;
    _nameMatchConfirmed = false;
    _nameConflicts = 0;

    final fallbackKey = _keyOf(info);
    final fallback = _sink.find(fallbackKey);
    final current = _song;
    if (fallback != null &&
        fallback.replayable &&
        fallback.key != (current?.key ?? '')) {
      _key = fallbackKey;
      _song = fallback;
      _mode = LyricMode.replay;
      _syncFilling();
      _lastCapturedText = fallback.lines.last.text;
      debugPrint('[LyricCar] 改按「换歌后那套 key」回放《${fallback.displayName}》');
      return;
    }

    // 两条路都没有 → 新开一份稿子（名字匹配到的那份原样留着，可能只是重名）
    _key = fallbackKey;
    _song = StoredSong(
      key: _key,
      title: _displayTitle(info),
      songName: _realSongName,
      duration: info.duration,
      fromStart: _startedFromStart,
      lines: const [],
    );
    _mode = LyricMode.capturing;
    _filling = false;
    _primedTitle = true;
    _lastCapturedText = '';
    debugPrint('[LyricCar] 名字匹配不可信 → 按新稿采集');
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
    // 真歌名要在**切歌这一瞬间**就抓住：歌词写在歌曲名行时，标题过一两秒就变成
    // 歌词了，晚一步就再也拿不到歌名（见 [_pendingTitleName]）。
    _realSongName = _realNameOf(info);
    _pendingTitleName = (_titleRowMode && _realSongName.isEmpty)
        ? info.title.trim()
        : '';
    _nameMatched = false;
    _nameMatchConfirmed = false;
    _nameConflicts = 0;
    _currentLyricText = '';
    _textIndex = null;

    _startedFromStart = info.position.inMilliseconds <= kFromStartToleranceMs;
    // 换歌那一刻的位置很可能是上一首留下的僵尸值（蓝牙协议栈晚一拍才把新歌的
    // 播放状态发过来）。这时先不下「缺开头」的结论，等它掉回开头 —— 见
    // [_resolveStartUndecided]。App 自己刚起会话时不适用（那确实是半路插进来的）。
    _startUndecided = songChange && !_startedFromStart;

    _minPositionMs = info.position.inMilliseconds;
    _maxPositionMs = info.position.inMilliseconds;
    final stored = _sink.find(_key);
    _songHadRecord = stored != null;

    // ① 先按**歌名**找：大部分歌的歌名是唯一的，而且歌名不受时长抖动影响。
    final byName = _matchByName(info);
    if (byName != null) {
      _song = byName;
      // ⚠️ key 用稿子自己的：这一遍算出来的 key 可能因为时长抖动不一样，
      // 换个 key 存就会发现「同一首歌有两份」。
      _key = byName.key;
      _songHadRecord = true;
      _mode = LyricMode.replay;
      _nameMatched = true;
      _syncFilling();
      // 不锁「上一句」：验证要靠接下来这几句歌词本身，锁了就把重复的句子挡掉了
      _lastCapturedText = '';
      debugPrint('[LyricCar] 按歌名匹配到《${byName.displayName}》→ 直接回放');
      return;
    }

    // ② 歌名对不上（或重名分不清）→ 退回「换歌后那套 key」
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
      songName: _realSongName.isNotEmpty
          ? _realSongName
          : (stored?.songName ?? ''),
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
    _realSongName = '';
    _pendingTitleName = '';
    _nameMatched = false;
    _nameMatchConfirmed = false;
    _nameConflicts = 0;
    _positionChangedAtMs = 0;
    _lyricSeenAtMs = 0;
    _currentLyricText = '';
    _textIndex = null;
    _loggedStuck = false;
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

    // 切歌后第一眼那一句如果是**真歌名**（歌手行不带「歌曲名 - 歌手」时只能这么抓），
    // 认下来当歌名，别当成歌词。
    if (_titleRowMode &&
        _realSongName.isEmpty &&
        _pendingTitleName.isNotEmpty &&
        normalizeTitle(text) == normalizeTitle(_pendingTitleName)) {
      _realSongName = text.trim();
      _lastCapturedText = text;
      debugPrint('[LyricCar] 记下真歌名：$_realSongName');
      return;
    }
    // 播放器卡住不报位置时，每句歌词都会被记成同一个假时间戳（常常正好是时长），
    // 存进去就是废物；而且没位置也没法验「名字匹配对不对」。这一遍只做回放。
    if (_positionStuck(info, _nowMs())) {
      if (!_loggedStuck) {
        _loggedStuck = true;
        debugPrint(
          '[LyricCar] 播放器不再报播放位置（卡在 ${info.position.inSeconds}s）→ 只回放，先不采集',
        );
      }
      _lastCapturedText = text;
      return;
    }

    // 名字匹配到的稿子还没验过：先用歌词确认，确认之前一个字都不写
    if (_nameMatched && !_nameMatchConfirmed) {
      if (!_verifyNameMatch(info, text)) return;
      if (!_filling) return; // 已收工的稿子不再回写
    }
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
      songName: _realSongName.isNotEmpty ? _realSongName : base.songName,
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

  /// 播放器报的播放位置是不是不可信了。
  ///
  /// 两个信号，都得先满足「歌词还在往前走」（否则就是把歌暂停了，位置本来就不动）：
  ///   * 位置已经跑到时长上甚至超过它 —— 播放器不可能一直停在结尾还继续唱；
  ///   * 位置半天不动（[staleAfter]）。
  bool _positionStuck(MediaInfo info, int nowMs) {
    if (nowMs - _lyricSeenAtMs > aliveWindow.inMilliseconds) return false;
    final total = info.duration.inMilliseconds;
    final position = info.position.inMilliseconds;
    if (total > 0 && position >= total - 500) return true;
    if (_positionChangedAtMs == 0) return false;
    return nowMs - _positionChangedAtMs > staleAfter.inMilliseconds;
  }

  /// 记下「刚才那一句歌词」，并在稿子里找它 —— 位置卡住时靠它定位当前句。
  void _trackLyricText(MediaInfo info, int nowMs) {
    final lyric = _lyricTextOf(info);
    if (lyric.isEmpty || lyric == _currentLyricText) return;
    _currentLyricText = lyric;
    _lyricSeenAtMs = nowMs;
    final lines = _song?.lines;
    if (lines == null || lines.isEmpty) return;
    _textIndex = _locateLine(lines, lyric);
  }

  /// 这一眼有没有一句「歌词」（不含真歌名、不含「歌曲名 - 歌手」这种身份信息）。
  String _lyricTextOf(MediaInfo info) {
    if (_titleRowMode) {
      final title = info.title.trim();
      if (title.isEmpty) return '';
      if (_realSongName.isNotEmpty &&
          normalizeTitle(title) == normalizeTitle(_realSongName)) {
        return '';
      }
      return title;
    }
    final artist = info.artist.trim();
    if (artist.isEmpty) return '';
    if (looksLikeTitleArtistLine(artist, info.title)) return '';
    if (normalizeTitle(artist) == normalizeTitle(info.title)) return '';
    return artist;
  }

  /// 在稿子里找这句话。从上次的位置往后找起：副歌重复时同一句会出现好几次，
  /// 往后找才跟得上播放。
  int? _locateLine(List<LyricLine> lines, String text) {
    final from = (_textIndex ?? -1).clamp(-1, lines.length - 1) + 1;
    for (var i = from; i < lines.length; i++) {
      if (lines[i].text == text) return i;
    }
    for (var i = 0; i < from; i++) {
      if (lines[i].text == text) return i;
    }
    return null;
  }

  void _publish(MediaInfo info, int nowMs) {
    final lines = _song?.lines ?? const <LyricLine>[];
    final stalled = _positionStuck(info, nowMs);
    // 位置卡住时：用「按歌词文本认出来的那一句」当当前句，位置也用它算，
    // 不然进度条永远是满的、高亮永远最后一句
    final textIndex = stalled ? _textIndex : null;

    final index = switch (_mode) {
      LyricMode.idle => -1,
      // 回放：按播放位置高亮（半份稿子也一样 —— 前面的句子照滚，
      // 还没采到的位置返回 -1，界面就不会乱指）
      LyricMode.replay => textIndex ?? lyricIndexAt(lines, info.position),
      // 采集：最新抓到的那句就是「当前句」
      LyricMode.capturing => lines.isEmpty ? -1 : lines.length - 1,
    };

    Duration? derived;
    if (stalled &&
        textIndex != null &&
        textIndex >= 0 &&
        textIndex < lines.length) {
      derived = Duration(milliseconds: lines[textIndex].timeMs);
    }

    _state = LyricSessionState(
      media: info,
      mode: _mode,
      song: _song,
      lines: lines,
      currentIndex: index,
      complete: _song?.complete ?? false,
      storedCount: _sink.count,
      collecting: _mode == LyricMode.capturing || _filling,
      derivedPosition: derived,
      playingNow:
          info.playing || nowMs - _lyricSeenAtMs <= aliveWindow.inMilliseconds,
      positionStale: stalled,
    );
  }
}
