import 'dart:convert';
import 'dart:io';

import 'package:lyriccar/features/carlyric/data/lyric_store.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';
import 'package:flutter_test/flutter_test.dart';

StoredSong _song({
  required String title,
  Duration duration = const Duration(minutes: 3),
  bool complete = true,
  bool fromStart = true,
  LyricSource source = LyricSource.captured,
  required List<String> texts,
}) => StoredSong(
  key: songKey(title: title, duration: duration),
  title: title,
  artist: '歌手',
  duration: duration,
  complete: complete,
  fromStart: fromStart,
  source: source,
  lines: [
    for (var i = 0; i < texts.length; i++)
      LyricLine(timeMs: i * 5000, text: texts[i]),
  ],
);

void main() {
  // ==========================================================
  // 落盘策略：连续采集时不能每一句都整份重写
  //
  // 真机上量过：500 首（存储 657KB）一次「编码 + 写盘」约 160ms，而歌词两三秒
  // 来一句 —— 每句都写就是把界面线程按住不放。所以第一次 300ms 后写，之后
  // 每 8 秒最多一次；「新歌第一句 / 采齐 / 删 / 导入」立刻写。
  // ==========================================================
  group('落盘策略', () {
    late Directory dir;

    setUp(() => dir = Directory.systemTemp.createTempSync('lyriccar_save_policy'));
    tearDown(() {
      if (dir.existsSync()) dir.deleteSync(recursive: true);
    });

    File storeFile() => File('${dir.path}/${LyricStore.fileName}');

    Map<String, Object?> readFile() =>
        (jsonDecode(storeFile().readAsStringSync()) as Map)
            .cast<String, Object?>();

    List<String> titlesOnDisk() => [
      for (final item in readFile()['songs'] as List)
        (item as Map)['title'].toString(),
    ];

    test('同一首连续采几句：只写一次，flush 之后一次补齐', () async {
      final store = await LyricStore.open(directory: dir);
      // 已经有一首老歌落好了（新歌才会立刻写，老歌的后续句子走合并）
      store.put(_song(title: '老歌', texts: ['甲', '乙', '丙']));
      await store.flush();

      final before = storeFile().lastModifiedSync();
      // 连着来三句（间隔小于 8 秒，应该被合起来）
      store.put(_song(title: '老歌', texts: ['甲', '乙', '丙', '丁']));
      store.put(_song(title: '老歌', texts: ['甲', '乙', '丙', '丁', '戊']));
      store.put(_song(title: '老歌', texts: ['甲', '乙', '丙', '丁', '戊', '己']));
      await Future<void>.delayed(const Duration(milliseconds: 600));

      expect(
        storeFile().lastModifiedSync(),
        before,
        reason: '合起来写：600ms 内不该落盘三次',
      );
      expect(titlesOnDisk(), ['老歌']);

      await store.flush();
      final lines = ((readFile()['songs'] as List).single as Map)['lines'] as List;
      expect(lines.length, 6, reason: 'flush 之后一句都不能少');
    });

    test('新歌第一次出现就立刻落盘（不用等合并窗口）', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '刚听到的新歌', texts: ['第一句']));
      // 给写盘一点时间（编码 + 写文件是异步的）
      await Future<void>.delayed(const Duration(milliseconds: 400));
      expect(titlesOnDisk(), ['刚听到的新歌']);
    });

    test('采齐的那一刻立刻落盘', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '半成品', complete: false, fromStart: true, texts: ['甲']));
      store.put(_song(title: '老歌', texts: ['甲', '乙']));
      await store.flush();

      final before = storeFile().lastModifiedSync();
      // 同一首歌从「没采齐」变成「采齐了」 —— 这就是「以后能直接回放」的凭据
      store.put(_song(title: '半成品', complete: true, fromStart: true, texts: ['甲', '乙']));
      await Future<void>.delayed(const Duration(milliseconds: 400));

      expect(
        storeFile().lastModifiedSync(),
        isNot(before),
        reason: '采齐要马上存下来',
      );
      final saved = (readFile()['songs'] as List)
          .cast<Map>()
          .firstWhere((song) => song['title'] == '半成品');
      expect(saved['complete'], isTrue);
    });

    test('写盘走临时文件改名，不会留下 .tmp，也不会写坏原文件', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '新歌', texts: ['甲', '乙']));
      await store.flush();

      expect(File('${storeFile().path}.tmp').existsSync(), isFalse, reason: '临时文件要改名走');
      expect(titlesOnDisk(), ['新歌'], reason: '内容要完整可解析');
    });

    test('连续采集会合并落盘：20 句不该整份重写 20 次', () async {
      // 真机上 500 首时一次整份重写要 160ms，所以「每句都写」是不能接受的。
      // 把真机 8 秒的合并窗口压成 200ms，量的还是同一套逻辑（含真实写盘）。
      final store = await LyricStore.open(
        directory: dir,
        writeInterval: const Duration(milliseconds: 200),
      );

      store.put(_song(title: '新歌', complete: false, texts: ['第 1 句']));
      await Future<void>.delayed(const Duration(milliseconds: 150));
      expect(store.rewriteCount, 1, reason: '新歌第一句要立刻落盘');

      for (var i = 2; i <= 20; i++) {
        store.put(
          _song(
            title: '新歌',
            complete: false,
            texts: [for (var j = 1; j <= i; j++) '第 $j 句'],
          ),
        );
        await Future<void>.delayed(const Duration(milliseconds: 50));
      }
      await store.flush();

      expect(
        store.rewriteCount,
        lessThanOrEqualTo(8),
        reason: '20 句跨 1 秒、窗口 200ms → 最多几次；绝不能一句一写',
      );
      expect(
        store.rewriteCount,
        greaterThan(1),
        reason: '但也要真的落过盘，不是一直攒着',
      );
      expect(store.songs.single.lines.length, 20);
      final saved = (readFile()['songs'] as List).single as Map;
      expect((saved['lines'] as List).length, 20, reason: 'flush 之后一句不少');
    });

    test('写完能重新打开（改名之后路径还是原来那个）', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '新歌', texts: ['甲', '乙']));
      await store.flush();

      final reopened = await LyricStore.open(directory: dir);
      expect(reopened.count, 1);
      expect(reopened.songs.single.lines.length, 2);
    });
  });

  late Directory dir;

  setUp(() {
    dir = Directory.systemTemp.createTempSync('lyriccar_store_test');
  });

  tearDown(() {
    if (dir.existsSync()) dir.deleteSync(recursive: true);
  });

  test('写入后重新打开还在（真正落盘）', () async {
    final store = await LyricStore.open(directory: dir);
    store.put(_song(title: '夜曲', texts: ['第一句', '第二句']));
    expect(store.count, 1);
    await store.flush();

    final reopened = await LyricStore.open(directory: dir);
    final song = reopened.find(songKey(title: '夜曲', duration: const Duration(minutes: 3)));
    expect(song, isNotNull);
    expect(song!.title, '夜曲');
    expect(song.lines.map((line) => line.text), ['第一句', '第二句']);
    expect(song.complete, isTrue);
    expect(song.artist, '歌手');
  });

  test('文件坏掉时当成空库，不抛异常', () async {
    File('${dir.path}/${LyricStore.fileName}').writeAsStringSync('{ 这不是 json');
    final store = await LyricStore.open(directory: dir);
    expect(store.count, 0);
  });

  test('删掉一首之后重新打开也不会复活', () async {
    final store = await LyricStore.open(directory: dir);
    final key = songKey(title: '夜曲', duration: const Duration(minutes: 3));
    store.put(_song(title: '夜曲', texts: ['第一句', '第二句']));
    expect(store.remove(key), isTrue);
    expect(store.remove(key), isFalse);
    await store.flush();

    final reopened = await LyricStore.open(directory: dir);
    expect(reopened.count, 0);
  });

  group('导出 / 导入', () {
    test('导出的是可以再导入回来的 JSON', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '夜曲', texts: ['A', 'B', 'C']));

      final document = jsonDecode(store.exportJson()) as Map<String, dynamic>;
      expect(document['version'], LyricStore.documentVersion);
      expect(document['count'], 1);
      expect((document['songs'] as List).length, 1);

      // 换台车机：新库是空的，导入后内容一致
      final other = await LyricStore.open(
        directory: Directory.systemTemp.createTempSync('lyriccar_store_test_b'),
      );
      final result = other.importJson(store.exportJson());
      expect(result.ok, isTrue);
      expect(result.added, 1);
      expect(other.count, 1);

      final imported = other.songs.single;
      expect(imported.title, '夜曲');
      expect(imported.lines.map((line) => line.text), ['A', 'B', 'C']);
      expect(imported.source, LyricSource.imported);
    });

    test('导入更完整的版本会覆盖本地的半份', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '夜曲', complete: false, texts: ['A']));

      final incoming = _song(title: '夜曲', complete: true, texts: ['A', 'B', 'C']);
      final result = store.importJson(
        jsonEncode({'version': 1, 'songs': [incoming.toJson()]}),
      );

      expect(result.updated, 1);
      expect(store.songs.single.lines.length, 3);
      expect(store.songs.single.complete, isTrue);
    });

    test('导入更差的版本不会覆盖本地更好的版本', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '夜曲', texts: ['A', 'B', 'C']));

      final worse = _song(title: '夜曲', complete: false, texts: ['A']);
      final result = store.importJson(
        jsonEncode({'version': 1, 'songs': [worse.toJson()]}),
      );

      expect(result.skipped, 1);
      expect(store.songs.single.lines.length, 3);
    });

    test('文件不合法时给一句人话的错误，而不是抛异常', () async {
      final store = await LyricStore.open(directory: dir);
      expect(store.importJson('不是 json').ok, isFalse);
      expect(store.importJson('{"a":1}').ok, isFalse);
      expect(store.importJson('{"songs":{}}').ok, isFalse);
      expect(store.count, 0);
    });

    test('导入时丢掉只有一句的垃圾条目', () async {
      final store = await LyricStore.open(directory: dir);
      final junk = _song(title: '只有一句', texts: ['就一句']);
      final result = store.importJson(
        jsonEncode({'version': 1, 'songs': [junk.toJson()]}),
      );
      expect(result.added, 0);
      expect(result.skipped, 1);
      expect(store.count, 0);
    });
  });

  group('缺开头的稿子（没有从头开始听）', () {
    test('老文件没有 fromStart 字段：第一句在开头附近 → 认成从头听的', () {
      final legacy =
          '{"key":"夜曲|180","title":"夜曲","durationMs":180000,'
          '"complete":true,"lines":[{"t":800,"text":"第一句"},{"t":9000,"text":"第二句"}]}';
      final song = StoredSong.fromJson(jsonDecode(legacy));
      expect(song!.fromStart, isTrue);
      expect(song.ready, isTrue);
    });

    test('老文件没有 fromStart 字段：第一句在很后面 → 认成缺开头，不当作完整', () {
      final legacy =
          '{"key":"夜曲|180","title":"夜曲","durationMs":180000,'
          '"complete":true,"lines":[{"t":75000,"text":"中间句"},{"t":80000,"text":"后面句"}]}';
      final song = StoredSong.fromJson(jsonDecode(legacy));
      expect(song!.fromStart, isFalse);
      expect(song.missingStart, isTrue);
      expect(song.ready, isFalse, reason: '缺开头的残稿不能算齐了');
    });

    test('fromStart 往返一致', () {
      final song = _song(title: '夜曲', fromStart: false, texts: ['A', 'B']);
      final restored = StoredSong.fromJson(song.toJson());
      expect(restored!.fromStart, isFalse);
    });

    test('导入时「齐了的」压过「更长但缺开头的」', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(
        _song(
          title: '夜曲',
          fromStart: false,
          complete: false,
          texts: ['A', 'B', 'C', 'D', 'E'],
        ),
      );
      final good = _song(title: '夜曲', fromStart: true, texts: ['A', 'B']);
      final result = store.importJson(
        jsonEncode({'version': 1, 'songs': [good.toJson()]}),
      );
      expect(result.updated, 1);
      expect(store.songs.single.ready, isTrue);
      expect(store.songs.single.lines.length, 2);
    });

    test('导入时「缺开头的」不会顶掉本地「齐了的」', () async {
      final store = await LyricStore.open(directory: dir);
      store.put(_song(title: '夜曲', fromStart: true, texts: ['A', 'B']));
      final worse = _song(
        title: '夜曲',
        fromStart: false,
        texts: ['A', 'B', 'C', 'D'],
      );
      final result = store.importJson(
        jsonEncode({'version': 1, 'songs': [worse.toJson()]}),
      );
      expect(result.skipped, 1);
      expect(store.songs.single.lines.length, 2);
    });
  });

  test('歌词库按最近更新排序', () async {
    final store = await LyricStore.open(directory: dir);
    store.put(
      _song(title: '早的', texts: ['A', 'B']).copyWith(updatedAtMs: 1000),
    );
    store.put(
      _song(title: '晚的', texts: ['A', 'B']).copyWith(updatedAtMs: 2000),
    );
    expect(store.songs.first.title, '晚的');
  });
}
