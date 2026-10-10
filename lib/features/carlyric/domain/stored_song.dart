// ============================================================
// 一首歌的完整歌词（存下来 / 导出去的那个东西）
// ============================================================
import 'lyric_line.dart';
import 'song_key.dart';

/// 歌词的来源，界面与导出文件里会标注。
enum LyricSource {
  /// 播放时一句一句采集回来的。
  captured('采集'),

  /// 从别人导出的歌词文件里导入的。
  imported('导入');

  const LyricSource(this.label);

  final String label;

  static LyricSource fromName(String? name) => LyricSource.values.firstWhere(
    (source) => source.name == name,
    orElse: () => LyricSource.captured,
  );
}

/// 「算作从头开始的」位置容差。
///
/// 播放器刚起播时位置可能已经走了一两秒（元数据推送有延迟），所以不能要求
/// 正好 0 秒。超过这个值才认为「我们是从中间插进来的，开头那几句没听到」。
const int kFromStartToleranceMs = 3000;

class StoredSong {
  StoredSong({
    required this.key,
    required this.title,
    this.artist = '',
    this.album = '',
    this.duration = Duration.zero,
    this.source = LyricSource.captured,
    this.complete = false,
    this.fromStart = false,
    this.updatedAtMs = 0,
    required this.lines,
  });

  /// [songKey] 算出来的主键。
  final String key;

  final String title;

  /// 真实歌手（采集时不一定拿得到 —— 歌手行可能正被歌词占着）。
  final String artist;

  final String album;

  final Duration duration;

  final LyricSource source;

  /// 是否已经「整首放完」，即歌词采集完整。下一遍直接照着这个播放。
  final bool complete;

  /// 采集是不是**从这首歌的开头**就开始盯着的。
  ///
  /// 这一位很关键：光看「放到尾巴了」是不够的 —— 如果是重装 App / 中途打开时
  /// 这首歌已经唱了一半，我们只采到了后半段。那种残缺稿**不能标成完整**，
  /// 否则下一遍就会照着这份缺开头的歌词回放，而且再也不会去补。
  ///
  /// 标成「没采全」之后，下次从头放一遍就会把前面缺的补上（并库按文本 + 时间
  /// 判重，不会重复），到那时才真正算收工。
  final bool fromStart;

  final int updatedAtMs;

  /// 按时间升序的歌词行。
  final List<LyricLine> lines;

  /// 至少要两句才算「有歌词」—— 只抓到一句多半是把真歌手/真专辑误当歌词了。
  bool get usable => lines.length >= 2;

  /// 还缺开头那几句（上一次是从中间开始听的）。
  bool get missingStart => !fromStart;

  /// 歌词齐了：既是从头开始采的，也放到过尾巴。
  bool get ready => complete && fromStart && usable;

  /// 有稿子就能**照着回放** —— 不必等它采齐。
  ///
  /// 这一位是「半份稿子也能滚歌词」的关键：以前只有 [ready] 才进回放，所以一首
  /// 只采到一分半的歌，重听时只会傻站着显示最后一句（车主反馈）。现在只要有两句
  /// 以上就按时间戳同步显示，缺的部分再慢慢补（见 [needsWork]）。
  bool get replayable => usable;

  /// 这篇稿子还没收工：要么没放到尾巴，要么缺开头。回放的同时要继续补。
  bool get needsWork => !complete || !fromStart;

  StoredSong copyWith({
    String? key,
    String? title,
    String? artist,
    String? album,
    Duration? duration,
    LyricSource? source,
    bool? complete,
    bool? fromStart,
    int? updatedAtMs,
    List<LyricLine>? lines,
  }) => StoredSong(
    key: key ?? this.key,
    title: title ?? this.title,
    artist: artist ?? this.artist,
    album: album ?? this.album,
    duration: duration ?? this.duration,
    source: source ?? this.source,
    complete: complete ?? this.complete,
    fromStart: fromStart ?? this.fromStart,
    updatedAtMs: updatedAtMs ?? this.updatedAtMs,
    lines: lines ?? this.lines,
  );

  Map<String, Object?> toJson() => {
    'key': key,
    'title': title,
    'artist': artist,
    'album': album,
    'durationMs': duration.inMilliseconds,
    'source': source.name,
    'complete': complete,
    'fromStart': fromStart,
    'updatedAtMs': updatedAtMs,
    'lines': [for (final line in lines) line.toJson()],
  };

  static StoredSong? fromJson(Object? json) {
    if (json is! Map) return null;
    final title = json['title']?.toString() ?? '';

    final duration = Duration(milliseconds: _int(json['durationMs']));
    final rawKey = json['key']?.toString() ?? '';
    // key 是可推导的，文件里缺失或写错都按当前规则重算，避免版本间对不上。
    final key = rawKey.isEmpty
        ? songKey(title: title, duration: duration)
        : rawKey;

    final rawLines = json['lines'];
    if (rawLines is! List) return null;
    final lines = <LyricLine>[];
    for (final item in rawLines) {
      final line = LyricLine.fromJson(item);
      if (line != null) lines.add(line);
    }
    lines.sort((a, b) => a.timeMs.compareTo(b.timeMs));
    // ⚠️ 标题**允许为空**：歌词写在歌曲名行时真歌名拿不到（见 _displayTitle），
    // 那种记录的名字是「专辑 - 歌手」。所以有歌词就算有效，别按标题判空丢掉。
    if (lines.isEmpty) return null;

    // 老版本的导出文件里没有这一位（那时候还没意识到「从中间开始听」这个问题），
    // 只能估：第一句就落在开头附近，说明当时是从头听的。
    // 估错的代价只是「这首歌会再采一遍」，比反过来（缺开头却当成完整）小得多。
    final rawFromStart = json['fromStart'];
    final fromStart = rawFromStart is bool
        ? rawFromStart
        : lines.isNotEmpty && lines.first.timeMs <= kFromStartToleranceMs;

    return StoredSong(
      key: key,
      title: title,
      artist: json['artist']?.toString() ?? '',
      album: json['album']?.toString() ?? '',
      duration: duration,
      source: LyricSource.fromName(json['source']?.toString()),
      complete: json['complete'] == true,
      fromStart: fromStart,
      updatedAtMs: _int(json['updatedAtMs']),
      lines: lines,
    );
  }

  static int _int(Object? value) => value is num ? value.round() : 0;
}
