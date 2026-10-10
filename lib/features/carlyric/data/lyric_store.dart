// ============================================================
// 歌词库（本地持久化 + 导出 / 导入）
//
// 采集到的歌词按「一首歌一份」存在应用私有目录的一个 JSON 文件里：
//   <ApplicationSupport>/carlyric/lyrics.json
//
// 为什么不用数据库：一首歌几十行、整个库也就几百 KB，一次性读进内存最快，
// 也方便「打包成一个文件带走」。写入做了 300ms 防抖 —— 采集时每抓到一句
// 都要落盘，不防抖的话一首歌要写几十次文件。
//
// 导出 / 导入的就是这个 JSON 文件本身：换车机时把文件拷过去导入即可。
// ============================================================
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../domain/lyric_capture_engine.dart';
import 'lyric_lan_server.dart';
import '../domain/stored_song.dart';

/// 导入结果，用来给用户一句人话的反馈。
class ImportResult {
  const ImportResult({
    this.added = 0,
    this.updated = 0,
    this.skipped = 0,
    this.error,
  });

  final int added;
  final int updated;
  final int skipped;
  final String? error;

  bool get ok => error == null;

  String get summary {
    if (error != null) return '导入失败：$error';
    if (added == 0 && updated == 0) {
      return '文件里没有新的歌词（跳过 $skipped 首）';
    }
    return '导入完成：新增 $added 首，更新 $updated 首，跳过 $skipped 首';
  }
}

/// 应用私有目录，歌词库与设置都放这儿。
Future<Directory> carlyricDirectory() async {
  final support = await getApplicationSupportDirectory();
  return Directory('${support.path}/carlyric');
}

/// [LyricLibrary] 是局域网传输那边要用的窄接口（只要 count / exportJson /
/// importJson），LyricStore 顺带实现它 —— Dart 不会自动按结构匹配接口。
class LyricStore implements LyricSink, LyricLibrary {
  LyricStore._(this._file, this._songs, this._minWriteInterval);

  static const int documentVersion = 1;
  static const String fileName = 'lyrics.json';

  final File _file;
  final Map<String, StoredSong> _songs;

  Timer? _saveTimer;
  Future<void>? _pendingSave;

  /// 打开（或新建）歌词库。
  ///
  /// [directory] 和 [writeInterval] 是给测试用的：后者能把「连续变化合并成
  /// 每 8 秒一次」压缩成几百毫秒来量（量的是同一套合并逻辑）。
  static Future<LyricStore> open({
    Directory? directory,
    Duration? writeInterval,
  }) async {
    final dir = directory ?? await carlyricDirectory();
    if (!dir.existsSync()) dir.createSync(recursive: true);
    final file = File('${dir.path}/$fileName');
    final songs = <String, StoredSong>{};

    if (file.existsSync()) {
      try {
        final decoded = jsonDecode(await file.readAsString());
        final raw = decoded is Map ? decoded['songs'] : null;
        // 导出文件里 songs 是**列表**（顺序稳定、人也看得懂）；
        // Map 形式是早期写法，顺手也认，免得老文件直接读不出来。
        if (raw is List) {
          for (final item in raw) {
            final song = StoredSong.fromJson(item);
            if (song != null) songs[song.key] = song;
          }
        } else if (raw is Map) {
          for (final entry in raw.entries) {
            final song = StoredSong.fromJson(entry.value);
            if (song != null) songs[song.key] = song;
          }
        }
      } catch (error) {
        // 文件坏了也不能让 App 起不来：当成空的库，并在下次写入时覆盖掉。
        debugPrint('歌词库解析失败，已忽略：$error');
      }
    }
    return LyricStore._(file, songs, writeInterval ?? minWriteInterval);
  }

  // ----------------------------------------------------------
  // LyricSink
  // ----------------------------------------------------------
  @override
  StoredSong? find(String key) => _songs[key];

  @override
  int get count => _songs.length;

  /// 库内容的版本号：内容一变就 +1。
  ///
  /// 界面（歌词库列表）挂在上面，这样采集到新的一句、或者引擎把「听了几秒就
  /// 切走」的歌删掉时，列表会自己跟着变 —— 不用谁记得去 refresh。
  final ValueNotifier<int> revision = ValueNotifier<int>(0);

  @override
  void put(StoredSong song) {
    final existing = _songs[song.key];
    _songs[song.key] = song;
    _bump();
    // 「这首歌第一次出现」和「终于采齐了」是两个值得马上落盘的节点：
    // 前者是记录的开始，后者是「以后可以直接回放」的凭据。
    _scheduleSave(urgent: existing == null || (!existing.complete && song.complete));
  }

  void _bump() => revision.value++;

  // ----------------------------------------------------------
  // 读取 / 维护
  // ----------------------------------------------------------
  /// 全部歌词，按最近更新排序（歌词库列表用）。
  List<StoredSong> get songs {
    final list = _songs.values.toList();
    list.sort((a, b) => b.updatedAtMs.compareTo(a.updatedAtMs));
    return list;
  }

  @override
  bool remove(String key) {
    final removed = _songs.remove(key) != null;
    if (removed) {
      _bump();
      _scheduleSave(urgent: true);
    }
    return removed;
  }

  void clear() {
    _songs.clear();
    _bump();
    _scheduleSave(urgent: true);
  }

  // ----------------------------------------------------------
  // 导出 / 导入
  // ----------------------------------------------------------
  @override
  String exportJson() =>
      const JsonEncoder.withIndent('  ').convert(exportDocument());

  Map<String, Object?> exportDocument() => {
    'app': 'lyriccar',
    'version': documentVersion,
    'exportedAtMs': DateTime.now().millisecondsSinceEpoch,
    'count': _songs.length,
    'songs': [for (final song in songs) song.toJson()],
  };

  /// 把导出的 JSON 合并进当前歌词库。
  ///
  /// 合并规则偏保守：**不会**用更差的版本覆盖更好的版本 ——
  /// 导入的歌词行更多、或者本地那份还没采集完整时，才采用导入的那份。
  @override
  ImportResult importJson(String text) {
    final Object? decoded;
    try {
      decoded = jsonDecode(text);
    } catch (error) {
      return ImportResult(error: '不是合法的 JSON（$error）');
    }
    if (decoded is! Map) return const ImportResult(error: '文件格式不对');

    final raw = decoded['songs'];
    if (raw is! List) return const ImportResult(error: '文件里没有 songs 列表');

    var added = 0;
    var updated = 0;
    var skipped = 0;
    for (final item in raw) {
      final incoming = StoredSong.fromJson(item);
      if (incoming == null || !incoming.usable) {
        skipped++;
        continue;
      }
      final existing = _songs[incoming.key];
      final marked = incoming.copyWith(source: LyricSource.imported);
      if (existing == null) {
        _songs[marked.key] = marked;
        added++;
      } else if (_preferIncoming(existing, marked)) {
        _songs[marked.key] = marked;
        updated++;
      } else {
        skipped++;
      }
    }
    if (added > 0 || updated > 0) {
      _bump();
      _scheduleSave(urgent: true);
    }
    return ImportResult(added: added, updated: updated, skipped: skipped);
  }

  /// 合并取舍：**好的压过差的**。
  ///
  /// 「好」的排序比句数更重要 —— 一份缺开头的稿子就算更长也是残的：
  ///   1. 齐了的（从头采的 + 放到过尾巴）压过没齐的；
  ///   2. 都齐或都没齐时，句数多的压过句数少的。
  bool _preferIncoming(StoredSong existing, StoredSong incoming) {
    if (incoming.ready != existing.ready) return incoming.ready;
    if (incoming.lines.length != existing.lines.length) {
      return incoming.lines.length > existing.lines.length;
    }
    return incoming.complete && !existing.complete;
  }

  // ----------------------------------------------------------
  // 落盘
  // ----------------------------------------------------------
  /// 第一次变化后多久落盘（保持「刚采到就存下来了」的手感）。
  static const Duration firstWriteDelay = Duration(milliseconds: 300);

  /// 连续变化时，整份重写最多多久一次（测试里可以传更短的窗口）。
  ///
  /// 以前每采到一句就整份重写一次。真机上量过：500 首（存储 657KB）时一次
  /// 「编码 116ms + 写盘 44ms」，而歌词两三秒就来一句 —— 界面线程一直被打断，
  /// 滚动会一顿一顿。现在把连续变化合起来：第一次 300ms 后写，之后每 8 秒最多
  /// 一次；「新歌第一句 / 采齐 / 删歌 / 导入 / 退到后台」这些节点仍然立刻写。
  static const Duration minWriteInterval = Duration(seconds: 8);

  final Duration _minWriteInterval;
  DateTime? _lastWriteAt;
  bool _dirty = false;

  /// 「开始整份重写」的次数。诊断用：连续采集时它应该远小于「采到的句数」，
  /// 否则就是把整个库拿在界面线程上反复编码。
  int rewriteCount = 0;

  void _scheduleSave({bool urgent = false}) {
    _dirty = true;
    if (_saveTimer != null && !urgent) return; // 已经排着了，等它
    _saveTimer?.cancel();
    _saveTimer = null;

    if (urgent) {
      _pendingSave = _write();
      return;
    }
    final last = _lastWriteAt;
    final since = last == null ? null : DateTime.now().difference(last);
    final wait = (since == null || since >= _minWriteInterval)
        ? firstWriteDelay
        : _minWriteInterval - since;
    _saveTimer = Timer(wait, () {
      _saveTimer = null;
      _pendingSave = _write();
    });
  }

  /// 强制立刻落盘（退到后台 / 测试里用）。
  Future<void> flush() async {
    _saveTimer?.cancel();
    _saveTimer = null;
    await _write();
  }

  /// 落盘请求：**串成一条链**，同一时刻只有一个写在跑。
  ///
  /// 不串的话，「新歌立刻写」和「flush」会同时在写同一个文件（还都在写
  /// `xxx.tmp`）—— 测试里就是这么抓到的：flush 回来时文件还没写完。
  Future<void> _write() {
    final next = (_pendingSave ?? Future<void>.value()).then((_) => _writeNow());
    _pendingSave = next;
    return next;
  }

  Future<void> _writeNow() async {
    if (!_dirty) return;
    _dirty = false;
    rewriteCount++;
    _lastWriteAt = DateTime.now();
    try {
      // 先写临时文件再改名 —— 改名是原子的。直接覆盖原文件的话，写一半被
      // 系统杀掉 / 断电就会留下半截 JSON，下次启动会把整个歌词库读成空的。
      final temp = File('${_file.path}.tmp');
      await temp.writeAsString(jsonEncode(exportDocument()), flush: true);
      await temp.rename(_file.path);
    } catch (error) {
      _dirty = true; // 这次没成，留给下一次
      debugPrint('歌词库写入失败：$error');
    }
  }
}
