import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';
import 'package:flutter_test/flutter_test.dart';

/// 内存版歌词库，替掉写文件的 LyricStore，让采集规则能被纯逻辑测试覆盖。
class _FakeSink implements LyricSink {
  final Map<String, StoredSong> songs = {};

  @override
  StoredSong? find(String key) => songs[key];

  @override
  void put(StoredSong song) => songs[song.key] = song;

  @override
  bool remove(String key) => songs.remove(key) != null;

  @override
  int get count => songs.length;
}

MediaInfo _media({
  String title = '夜曲',
  String artist = '',
  String album = '',
  Duration duration = const Duration(minutes: 3),
  Duration position = Duration.zero,
  bool playing = true,
}) => MediaInfo(
  hasSession: true,
  packageName: 'com.example.player',
  appLabel: '测试播放器',
  title: title,
  artist: artist,
  album: album,
  duration: duration,
  playing: playing,
  position: position,
);

StoredSong _storedSong({
  required String title,
  Duration duration = const Duration(minutes: 3),
  bool complete = true,
  bool fromStart = true,
  required List<int> lineSeconds,
}) => StoredSong(
  key: songKey(title: title, duration: duration),
  title: title,
  duration: duration,
  complete: complete,
  fromStart: fromStart,
  lines: [
    for (final second in lineSeconds)
      LyricLine(timeMs: second * 1000, text: '第 $second 秒'),
  ],
);

void main() {
  group('起播基线', () {
    test('播放器起播时写进歌手行的「真歌手」不会被当成歌词', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '周杰伦'));
      expect(engine.state.lines, isEmpty, reason: '这是歌手，不是歌词');

      engine.handle(
        _media(artist: '一群嗜血的蚂蚁', position: const Duration(seconds: 10)),
      );
      expect(engine.state.lines.single.text, '一群嗜血的蚂蚁');
      expect(engine.state.lines.single.timeMs, 10000, reason: '时间戳要记成当时的位置');
    });

    test('播放器先只给标题、之后才补真歌手 —— 补上的歌手同样不会被当成歌词', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: ''));
      engine.handle(
        _media(artist: '周杰伦', position: const Duration(seconds: 2)),
      );
      expect(engine.state.lines, isEmpty);

      engine.handle(
        _media(artist: '第一句歌词', position: const Duration(seconds: 9)),
      );
      expect(engine.state.lines.single.text, '第一句歌词');
    });

    test('中途才打开 App 时，当场正在唱的那句收不到（从下一句开始）', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      // 打开时元数据里已经是歌词了 —— 分不清它是刚开始唱还是唱到一半，只能当基线丢掉
      engine.handle(
        _media(artist: '打开时正在唱的那句', position: const Duration(seconds: 63)),
      );
      expect(engine.state.lines, isEmpty);

      engine.handle(
        _media(artist: '下一句', position: const Duration(seconds: 68)),
      );
      expect(engine.state.lines.single.text, '下一句');
    });
  });

  group('采集规则', () {
    test('只在值变化时收，重复推送同一个值不会重复记', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: 'A', position: const Duration(seconds: 1)));
      engine.handle(_media(artist: 'A', position: const Duration(seconds: 2)));
      engine.handle(_media(artist: 'B', position: const Duration(seconds: 3)));
      engine.handle(_media(artist: 'A', position: const Duration(seconds: 4)));

      expect(engine.state.lines.map((line) => line.text), [
        'A',
        'B',
        'A',
      ], reason: '副歌重复出现要各记一次');
    });

    test('歌名被错写进歌词行时忽略', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: '夜曲', position: const Duration(seconds: 1)));
      expect(engine.state.lines, isEmpty);
    });

    test('超长文本不像歌词，忽略', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(
        _media(artist: 'x' * 200, position: const Duration(seconds: 1)),
      );
      expect(engine.state.lines, isEmpty);
    });

    test('指定专辑行时只看专辑行的变化', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.album,
      );

      engine.handle(_media(album: '真专辑'));
      engine.handle(
        _media(
          album: '歌词在专辑行',
          artist: '歌手换了个名字',
          position: const Duration(seconds: 5),
        ),
      );
      expect(engine.state.lines.single.text, '歌词在专辑行');
    });

    test('采集模式下高亮的是最新那句', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: 'A', position: const Duration(seconds: 1)));
      engine.handle(_media(artist: 'B', position: const Duration(seconds: 2)));
      expect(engine.state.currentIndex, 1);
      expect(engine.state.mode, LyricMode.capturing);
    });
  });

  group('自动判断歌词写在哪一行', () {
    test('两行同时改成不同内容（换歌时写真歌手 + 真专辑）→ 不采', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      engine.handle(_media(artist: '真歌手', album: '真专辑'));
      engine.handle(
        _media(
          artist: '另一个歌手',
          album: '另一个专辑',
          position: const Duration(seconds: 3),
        ),
      );
      expect(engine.state.lines, isEmpty);
    });

    test('只有一行在变 → 认那一行', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      engine.handle(_media(artist: '真歌手', album: '真专辑'));
      engine.handle(
        _media(
          artist: '第一句歌词',
          album: '真专辑',
          position: const Duration(seconds: 4),
        ),
      );
      engine.handle(
        _media(
          artist: '第二句歌词',
          album: '真专辑',
          position: const Duration(seconds: 8),
        ),
      );
      expect(engine.state.lines.map((line) => line.text), ['第一句歌词', '第二句歌词']);
    });

    test('两行同时改成同一句（有的播放器两行都写）→ 只采一次', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      engine.handle(_media(artist: '真歌手', album: '真专辑'));
      engine.handle(
        _media(
          artist: '同一句歌词',
          album: '同一句歌词',
          position: const Duration(seconds: 4),
        ),
      );
      expect(engine.state.lines.map((line) => line.text), ['同一句歌词']);
    });

    test('先歌手行后专辑行分两次推同一句 → 也只采一次', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      engine.handle(_media(artist: '真歌手', album: '真专辑'));
      engine.handle(
        _media(
          artist: '同一句歌词',
          album: '真专辑',
          position: const Duration(seconds: 4),
        ),
      );
      engine.handle(
        _media(
          artist: '同一句歌词',
          album: '同一句歌词',
          position: const Duration(seconds: 4, milliseconds: 120),
        ),
      );
      expect(engine.state.lines.map((line) => line.text), ['同一句歌词']);
    });
  });

  group('时长后到', () {
    test('时长从未知变成已知时，已采歌词搬到新 key，旧 key 残档清掉', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手', duration: Duration.zero));
      engine.handle(
        _media(
          artist: '第一句',
          duration: Duration.zero,
          position: const Duration(seconds: 5),
        ),
      );

      final unknownKey = songKey(title: '夜曲', duration: Duration.zero);
      expect(sink.find(unknownKey)?.lines.length, 1);

      engine.handle(
        _media(
          artist: '第一句',
          duration: const Duration(minutes: 3),
          position: const Duration(seconds: 6),
        ),
      );

      final knownKey = songKey(
        title: '夜曲',
        duration: const Duration(minutes: 3),
      );
      expect(sink.find(knownKey)?.lines.length, 1);
      expect(sink.songs.length, 1, reason: '旧 key 不能留在歌词库里');
      expect(engine.state.song?.key, knownKey);
    });
  });

  group('回放', () {
    test('存过完整歌词 → 直接回放，按位置高亮，且不再采集', () {
      final sink = _FakeSink();
      final song = _storedSong(title: '夜曲', lineSeconds: [0, 10, 20]);
      sink.songs[song.key] = song;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      final state = engine.handle(
        _media(artist: '真歌手', position: const Duration(seconds: 12)),
      );
      expect(state.mode, LyricMode.replay);
      expect(state.currentIndex, 1);

      final later = engine.handle(
        _media(artist: '歌词行也在动', position: const Duration(seconds: 25)),
      );
      expect(later.mode, LyricMode.replay);
      expect(later.currentIndex, 2);
      expect(later.lines.length, 3, reason: '回放模式下不该再往库里加东西');
    });

    test('只采了一半的存档 → 直接回放，一边回放一边接着补', () {
      final sink = _FakeSink();
      final partial = _storedSong(
        title: '夜曲',
        complete: false,
        lineSeconds: [0, 10],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      // 打开 App 时这首歌已经放到 1 秒处：半份稿子照样按时间戳滚
      final opening = engine.handle(
        _media(artist: '真歌手', position: const Duration(seconds: 1)),
      );
      expect(
        opening.mode,
        LyricMode.replay,
        reason: '半份稿子也能回放，不再钉在最后一句上（车主反馈的那个 bug）',
      );
      expect(opening.currentIndex, 0, reason: '高亮第 1 句');
      expect(opening.collecting, isTrue, reason: '还没采完，回放的同时继续补');

      // 往下唱：缺的后半段接着进库，已经采过的两句原样留着
      final later = engine.handle(
        _media(artist: '第三句', position: const Duration(seconds: 20)),
      );
      expect(later.mode, LyricMode.replay);
      expect(later.lines.map((line) => line.text), ['第 0 秒', '第 10 秒', '第三句']);
      expect(later.currentIndex, 2, reason: '高亮跟着播放位置往前走');
    });

    test('稿子完整之后就不再回写了', () {
      final sink = _FakeSink();
      final done = _storedSong(title: '夜曲', lineSeconds: [0, 10, 20]);
      sink.songs[done.key] = done;
      final before = sink.songs[done.key]!.lines.length;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      final state = engine.handle(
        _media(artist: '一句新歌词', position: const Duration(seconds: 25)),
      );

      expect(state.collecting, isFalse, reason: '收工的稿子别再往里加东西');
      expect(sink.songs[done.key]!.lines.length, before);
    });
  });

  group('半份稿子也能滚歌词（不用等采完）', () {
    test('中途退出再进来：按当前位置高亮，而不是钉在最后一句', () {
      final sink = _FakeSink();
      final partial = _storedSong(
        title: '夜曲',
        complete: false,
        lineSeconds: [0, 30, 60, 90, 120],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      // 退出 App 时这首歌才放到 100 秒，重新进来时它已经放到 100 秒处
      final state = engine.handle(
        _media(artist: '真歌手', position: const Duration(seconds: 100)),
      );

      expect(state.mode, LyricMode.replay);
      expect(state.lines.length, 5, reason: '只有前半段也能显示');
      expect(state.currentIndex, 3, reason: '100 秒 → 第 90 秒那句（旧行为是钉在最后一句不放）');
      expect(state.collecting, isTrue, reason: '还没采完，继续补后半段');
    });

    test('回放中把后半段听完 → 补进同一份稿子并收工', () {
      final sink = _FakeSink();
      const total = Duration(minutes: 4);
      final partial = _storedSong(
        title: '夜曲',
        duration: total,
        complete: false,
        lineSeconds: [0, 30, 60],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      engine.handle(
        _media(
          artist: '真歌手',
          duration: total,
          position: const Duration(seconds: 60),
        ),
      );

      // 后半段接着唱
      engine.handle(
        _media(
          artist: '第 90 秒',
          duration: total,
          position: const Duration(seconds: 90),
        ),
      );
      final state = engine.handle(
        _media(
          artist: '第 120 秒',
          duration: total,
          position: const Duration(seconds: 120),
        ),
      );
      expect(state.mode, LyricMode.replay, reason: '补的过程里歌词一直在滚');
      expect(state.lines.map((line) => line.text), [
        '第 0 秒',
        '第 30 秒',
        '第 60 秒',
        '第 90 秒',
        '第 120 秒',
      ]);

      // 放到尾巴 → 收工，不再回写
      final finished = engine.handle(
        _media(
          artist: '第 120 秒',
          duration: total,
          position: total - const Duration(seconds: 1),
        ),
      );
      expect(finished.complete, isTrue, reason: '从开头采到尾巴了');
      expect(finished.collecting, isFalse, reason: '收工就不再补');
      expect(sink.find(finished.song!.key)!.ready, isTrue);
    });

    test('回放中从头重放 → 从头滚，已采的句子不重复也不乱序', () {
      final sink = _FakeSink();
      const total = Duration(minutes: 5);
      final partial = _storedSong(
        title: '夜曲',
        duration: total,
        complete: false,
        lineSeconds: [0, 30, 60],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      engine.handle(_media(artist: '真歌手', duration: total));
      engine.handle(
        _media(
          artist: '第四句',
          duration: total,
          position: const Duration(seconds: 90),
        ),
      );
      expect(engine.state.lines.length, 4);

      // 用户手动跳回开头
      final restarted = engine.handle(
        _media(
          artist: '真歌手',
          duration: total,
          position: const Duration(seconds: 1),
        ),
      );
      expect(restarted.mode, LyricMode.replay);
      expect(restarted.currentIndex, 0, reason: '回到开头 → 高亮第 1 句');

      // 第二遍把前三句原样又送了一遍
      engine.handle(
        _media(
          artist: '第 0 秒',
          duration: total,
          position: const Duration(seconds: 0),
        ),
      );
      engine.handle(
        _media(
          artist: '第 30 秒',
          duration: total,
          position: const Duration(seconds: 31),
        ),
      );
      final state = engine.handle(
        _media(
          artist: '第 60 秒',
          duration: total,
          position: const Duration(seconds: 61),
        ),
      );

      expect(state.lines.length, 4, reason: '同一句重采不该变成新的一句');
      expect(state.lines.map((line) => line.text), [
        '第 0 秒',
        '第 30 秒',
        '第 60 秒',
        '第四句',
      ], reason: '顺序还按时间排，没有被打乱');
      expect(state.currentIndex, 2, reason: '重放时高亮跟着走');
    });

    test('稿子只有后半段 → 开头那段没高亮（不会乱指到已有句子上）', () {
      final sink = _FakeSink();
      final partial = _storedSong(
        title: '夜曲',
        complete: false,
        fromStart: false,
        lineSeconds: [60, 65, 70],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      final early = engine.handle(
        _media(artist: '真歌手', position: const Duration(seconds: 20)),
      );
      expect(early.mode, LyricMode.replay);
      expect(early.currentIndex, -1, reason: '20 秒处一句都还没采到，就别乱指');
      expect(early.fromStart, isFalse, reason: '界面会提示「开头那段还没采到」');

      // 采到 60 秒之后才有得高亮
      final later = engine.handle(
        _media(artist: '新句', position: const Duration(seconds: 62)),
      );
      expect(later.currentIndex, greaterThanOrEqualTo(0));
    });
  });

  group('采集完成', () {
    test('放到尾巴上就标记为完整', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(
        _media(artist: '真歌手', duration: const Duration(minutes: 3)),
      );
      engine.handle(
        _media(
          artist: '第一句',
          duration: const Duration(minutes: 3),
          position: const Duration(seconds: 5),
        ),
      );
      engine.handle(
        _media(
          artist: '第二句',
          duration: const Duration(minutes: 3),
          position: const Duration(seconds: 20),
        ),
      );
      expect(engine.state.complete, isFalse);

      final finished = engine.handle(
        _media(
          artist: '第二句',
          duration: const Duration(minutes: 3),
          position: const Duration(minutes: 3) - const Duration(seconds: 1),
        ),
      );
      expect(finished.complete, isTrue);
      expect(sink.find(finished.song!.key)?.complete, isTrue);
    });

    test('只采到一句不算完整（多半是把真歌手误当歌词了）', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(
        _media(artist: '真歌手', duration: const Duration(minutes: 3)),
      );
      engine.handle(
        _media(
          artist: '唯一一句',
          duration: const Duration(minutes: 3),
          position: const Duration(seconds: 30),
        ),
      );
      final finished = engine.handle(
        _media(
          artist: '唯一一句',
          duration: const Duration(minutes: 3),
          position: const Duration(minutes: 3) - const Duration(seconds: 1),
        ),
      );
      expect(finished.complete, isFalse);
    });
  });

  group('单曲循环 / 重听', () {
    test('放完一遍又从头放 → 第二遍立刻切进回放，且不复制歌词', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      const total = Duration(minutes: 3);

      engine.handle(_media(artist: '真歌手', duration: total));
      engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 5),
        ),
      );
      engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 60),
        ),
      );
      // 真的快放完了（播放器每 250ms 报一次位置，必然经过这里）
      engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: total - const Duration(seconds: 1),
        ),
      );
      expect(engine.state.complete, isTrue);

      // 单曲循环：位置跳回开头，标题没变
      final looped = engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 1),
        ),
      );
      expect(looped.mode, LyricMode.replay, reason: '第二遍应该直接看歌词');
      expect(looped.lines.length, 2, reason: '不能把整首歌复制一份');

      // 第二遍唱到第 6 秒 → 高亮第一句
      final later = engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 6),
        ),
      );
      expect(later.mode, LyricMode.replay);
      expect(later.currentIndex, 0);
    });

    test('只听了半首就重听 → 立刻有滚动歌词，同时把缺的补上', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      const total = Duration(minutes: 5);

      engine.handle(_media(artist: '真歌手', duration: total));
      engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 40),
        ),
      );
      engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 120),
        ),
      );

      // 跳回开头（手动重播）。没放完，不该被标成完整
      final restarted = engine.handle(
        _media(
          artist: '真歌手',
          duration: total,
          position: const Duration(seconds: 1),
        ),
      );
      expect(
        restarted.mode,
        LyricMode.replay,
        reason: '已经采到两句就够回放了 —— 第二遍从头立刻有歌词滚，缺的边放边补',
      );
      expect(restarted.complete, isFalse);
      expect(restarted.collecting, isTrue);
      expect(restarted.currentIndex, -1, reason: '刚回到开头，还没到第一句');

      // 新一遍把 A、B 又送了一遍，然后继续往下唱
      var state = engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 40),
        ),
      );
      state = engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 120),
        ),
      );
      expect(state.lines.length, 2, reason: '重采的 A、B 是同一句，不该入库两次');
      expect(state.currentIndex, 1, reason: '放到 120 秒 → 高亮 B');

      state = engine.handle(
        _media(
          artist: 'C',
          duration: total,
          position: const Duration(seconds: 200),
        ),
      );
      expect(state.lines.map((line) => line.text), ['A', 'B', 'C']);
      expect(state.currentIndex, 2, reason: '新采到的 C 立刻能同步高亮');
    });

    test('重听时同一句又送了一遍、位置还差得多 → 不重复入库', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      const total = Duration(minutes: 5);

      engine.handle(_media(artist: '真歌手', duration: total));
      engine.handle(
        _media(
          artist: 'A',
          duration: total,
          position: const Duration(seconds: 40),
        ),
      );
      engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 120),
        ),
      );

      // 跳回开头（位置 120s → 4s）
      engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 4),
        ),
      );
      // 播放器又把「正在唱的这句」送了一遍，位置还对不上（差 30 秒，
      // mergeLyricLine 的 1.5 秒窗口拦不住 —— 只能靠「上一句就是它」拦住）
      final state = engine.handle(
        _media(
          artist: 'B',
          duration: total,
          position: const Duration(seconds: 90),
        ),
      );

      expect(state.lines.map((line) => line.text), [
        'A',
        'B',
      ], reason: '同一句不该因为位置差得多就再收一遍');
    });
  });

  group('随手跳过的歌不留记录', () {
    // 用户开车时经常听几秒就切下一首（试听、找歌）。这种歌没采到几句、下次也不会
    // 再听，留在歌词库里只是垃圾 —— 所以「以前没记过的歌」听不够 15 秒就切走，
    // 直接不留。判据是**播放位置往前走了多少**（暂停、App 在后台都不算听）。
    test('新歌只听了 6 秒就切走 → 库里不留它', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: '甲', position: const Duration(seconds: 4)));
      engine.handle(_media(artist: '乙', position: const Duration(seconds: 6)));
      expect(sink.songs.length, 1, reason: '采的时候是写进去了');

      // 切下一首，它也采了几句
      engine.handle(
        _media(title: '下一首', artist: '别的歌手', position: Duration.zero),
      );
      engine.handle(
        // 位置往前走 + 歌词行换了 → 这一首才真的采到句子并存下来
        _media(
          title: '下一首',
          artist: '丙',
          position: const Duration(seconds: 30),
        ),
      );

      expect(sink.songs.length, 1, reason: '跳过的这首被删掉，只剩接着放的这首');
      expect(sink.songs.keys.single, contains('下一首'));
    });

    test('听够了 15 秒再切走 → 留下', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: '甲', position: const Duration(seconds: 20)));
      engine.handle(_media(artist: '乙', position: const Duration(seconds: 40)));

      engine.handle(
        _media(title: '下一首', artist: '别的歌手', position: Duration.zero),
      );
      engine.handle(
        // 位置往前走 + 歌词行换了 → 这一首才真的采到句子并存下来
        _media(
          title: '下一首',
          artist: '丙',
          position: const Duration(seconds: 30),
        ),
      );

      expect(sink.songs.length, 2, reason: '听了 40 秒，这首要留着');
      expect(
        sink.songs.containsKey(
          songKey(title: '夜曲', duration: const Duration(minutes: 3)),
        ),
        isTrue,
      );
    });

    test('库里本来就有稿子的歌，听两秒切走也不能删', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      final key = songKey(title: '夜曲', duration: const Duration(minutes: 3));
      sink.songs[key] = _storedSong(title: '夜曲', lineSeconds: [5, 20, 40]);

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: '甲', position: const Duration(seconds: 2)));

      engine.handle(
        _media(title: '下一首', artist: '别的歌手', position: Duration.zero),
      );
      engine.handle(
        // 位置往前走 + 歌词行换了 → 这一首才真的采到句子并存下来
        _media(
          title: '下一首',
          artist: '丙',
          position: const Duration(seconds: 30),
        ),
      );
      expect(sink.find(key), isNotNull, reason: '老稿子是攒下来的，不能被顺手清掉');
      expect(sink.find(key)!.lines.length, 3);
    });

    test('会话直接断开（没等到下一首）也按同一条规则判', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手'));
      engine.handle(_media(artist: '甲', position: const Duration(seconds: 3)));

      engine.handle(
        const MediaInfo(
          hasSession: false,
          sessionCount: 0,
          position: Duration.zero,
        ),
      );
      expect(sink.songs, isEmpty);
    });
  });

  group('中途才开始听（缺开头）', () {
    test('从中间插进来 → 就算放到了尾巴也不标完整，只记「缺开头」', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      const total = Duration(minutes: 4);

      // 打开 App 时这首歌已经唱到 1 分半了
      engine.handle(
        _media(
          artist: '♪ 中间某句 ♪',
          duration: total,
          position: const Duration(seconds: 90),
        ),
      );
      expect(engine.state.fromStart, isFalse, reason: '这一眼就已经在 90 秒处了');

      engine.handle(
        _media(
          artist: '♪ 下一句 ♪',
          duration: total,
          position: const Duration(seconds: 95),
        ),
      );
      engine.handle(
        _media(
          artist: '♪ 再下一句 ♪',
          duration: total,
          position: const Duration(seconds: 99),
        ),
      );

      // 放到尾巴上
      final ended = engine.handle(
        _media(
          artist: '♪ 再下一句 ♪',
          duration: total,
          position: total - const Duration(seconds: 1),
        ),
      );

      expect(ended.complete, isFalse, reason: '缺了开头，不能算收工');
      expect(ended.mode, LyricMode.capturing);
      expect(ended.lines.length, 2, reason: '第一句是插进来时的基线，丢掉');
      expect(sink.find(ended.song!.key)!.fromStart, isFalse);
    });

    test('下次从头放一遍 → 把开头补上，然后才标完整', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      const total = Duration(minutes: 4);

      // 第一遍：中途插进来，只采到后半段
      // （插进来时正在唱的那句会被当基线丢掉，所以这里要靠后面的变化才采得到）
      engine.handle(
        _media(
          artist: '♪ 后半段一 ♪',
          duration: total,
          position: const Duration(seconds: 120),
        ),
      );
      engine.handle(
        _media(
          artist: '♪ 后半段二 ♪',
          duration: total,
          position: const Duration(seconds: 125),
        ),
      );
      engine.handle(
        _media(
          artist: '♪ 后半段三 ♪',
          duration: total,
          position: const Duration(seconds: 130),
        ),
      );
      engine.handle(
        _media(
          artist: '♪ 后半段三 ♪',
          duration: total,
          position: total - const Duration(seconds: 1),
        ),
      );
      expect(engine.state.complete, isFalse, reason: '缺开头，不能收工');
      expect(engine.state.lines.length, 2);

      // 第二遍：用户从头放
      final reopened = engine.handle(
        _media(artist: '真歌手', duration: total, position: Duration.zero),
      );
      expect(
        reopened.mode,
        LyricMode.replay,
        reason: '已有那两句照样按时间戳滚（开头没采到就先只显示它们）',
      );
      expect(reopened.collecting, isTrue, reason: '缺开头，这一遍继续补');
      expect(reopened.lines.length, 2, reason: '上次那两句还在，这一遍只是补开头');

      final firstHead = engine.handle(
        _media(
          artist: '♪ 开头一 ♪',
          duration: total,
          position: const Duration(seconds: 5),
        ),
      );
      expect(
        firstHead.fromStart,
        isTrue,
        reason: '真采到开头那句了，「缺开头」才抹掉（不是一开播就自己变完整）',
      );
      engine.handle(
        _media(
          artist: '♪ 开头二 ♪',
          duration: total,
          position: const Duration(seconds: 10),
        ),
      );
      final finished = engine.handle(
        _media(
          artist: '♪ 开头二 ♪',
          duration: total,
          position: total - const Duration(seconds: 1),
        ),
      );

      expect(finished.lines.map((line) => line.text), [
        '♪ 开头一 ♪',
        '♪ 开头二 ♪',
        '♪ 后半段二 ♪',
        '♪ 后半段三 ♪',
      ]);
      expect(finished.complete, isTrue, reason: '开头补上了，也放到尾巴了');
      expect(finished.collecting, isFalse, reason: '收工了就不再补');
      expect(sink.find(finished.song!.key)!.ready, isTrue);
    });

    test('存过的稿子缺开头 → 照样回放，但「这一遍是从头听的」要真采回来才算数', () {
      final sink = _FakeSink();
      final partial = _storedSong(
        title: '夜曲',
        complete: true,
        fromStart: false,
        lineSeconds: [60, 65, 70],
      );
      sink.songs[partial.key] = partial;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );
      final state = engine.handle(
        _media(artist: '♪ 某句 ♪', duration: const Duration(minutes: 3)),
      );
      expect(state.mode, LyricMode.replay, reason: '残稿也能回放（总比钉在最后一句好）');
      expect(state.collecting, isTrue, reason: '缺开头 → 一边回放一边把开头采回来');
      expect(state.lines.length, 3, reason: '把残稿当起点接着采');

      // 往下唱到开头：新采到的句子并进同一份稿子，标记同时翻成「从开头听的」
      final filled = engine.handle(
        _media(
          artist: '♪ 开头句 ♪',
          duration: const Duration(minutes: 3),
          position: const Duration(seconds: 5),
        ),
      );
      expect(filled.fromStart, isTrue);
      expect(filled.lines.first.text, '♪ 开头句 ♪', reason: '新句子按时间插到最前面');
      expect(filled.lines.length, 4);
    });
  });

  group('没有媒体会话', () {
    test('回到 idle 并清空当前会话', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(
        _media(artist: '第一句', position: const Duration(seconds: 5)),
      );
      expect(engine.state.mode, LyricMode.capturing);

      final idle = engine.handle(const MediaInfo());
      expect(idle.mode, LyricMode.idle);
      expect(idle.lines, isEmpty);
      expect(idle.currentIndex, -1);
    });
  });

  group('切换设置', () {
    test('运行中换「歌词写在」立即生效', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(_media(artist: '真歌手', album: '真专辑'));
      engine.setField(LyricFieldPreference.album);
      engine.handle(
        _media(
          artist: '真歌手',
          album: '歌词其实在专辑行',
          position: const Duration(seconds: 5),
        ),
      );
      expect(engine.state.lines.single.text, '歌词其实在专辑行');
    });
  });
}
