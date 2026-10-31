// ============================================================
// 播放器卡住不报播放位置时的兜底
//
// 真机上抓到的（BYD 车机 + 蓝牙，14:40 那段日志）：
//   state=PAUSED, position=228309, speed=0   ← 一直是这个值，再也不动
//   metadata: TITLE / ARTIST / ALBUM 两秒换一句 ← 歌词明明在往前走
//
// 后果就是车主看到的样子：
//   * 进度条永远是满的（position == duration）
//   * 左右时间都是 2:12（位置被 clamp 到时长）
//   * 高亮永远停在最后一句（位置超过所有时间戳）
//   * 播放按钮显示「播放」，可音乐在放
//
// 兜底办法：位置半天不动、歌词却一直在变 → 认为位置不可信，
// 改用「按歌词文本在稿子里认出来的那一句」当当前句，位置也用它的时间戳。
// ============================================================
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';

class _FakeSink implements LyricSink {
  final Map<String, StoredSong> songs = {};

  @override
  StoredSong? find(String key) => songs[key];

  @override
  Iterable<StoredSong> get all => songs.values;

  @override
  void put(StoredSong song) => songs[song.key] = song;

  @override
  bool remove(String key) => songs.remove(key) != null;

  @override
  int get count => songs.length;
}

const _artist = '夜曲 - 周杰伦';
const _album = '十一月的肖邦';
const _total = Duration(minutes: 5);

MediaInfo _push(
  String lyric, {
  required Duration position,
  bool playing = true,
}) => MediaInfo(
  hasSession: true,
  title: lyric,
  artist: _artist,
  album: _album,
  duration: _total,
  position: position,
  playing: playing,
);

StoredSong _stored() => StoredSong(
  key: 'k',
  title: _artist,
  songName: '夜曲',
  duration: _total,
  complete: true,
  fromStart: true,
  lines: const [
    LyricLine(timeMs: 0, text: '第一句'),
    LyricLine(timeMs: 30000, text: '第二句'),
    LyricLine(timeMs: 60000, text: '第三句'),
    LyricLine(timeMs: 90000, text: '第四句'),
  ],
);

void main() {
  group('位置卡住时的兜底', () {
    test('位置不动、歌词在走 → 当前句按歌词认，位置按那句的时间戳算', () {
      var now = DateTime(2026, 10, 25, 14, 40);
      final sink = _FakeSink()..songs['k'] = _stored();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
        clock: () => now,
      );

      // 第一眼：位置就是那个假值（远超时长），歌词是稿子里的第三句
      var state = engine.handle(
        _push('第三句', position: const Duration(seconds: 228), playing: false),
      );
      expect(state.mode, LyricMode.replay);
      expect(state.currentIndex, 3, reason: '位置 228 秒比所有句子都晚 → 旧行为会指到「第四句」');

      // 7 秒后：位置还是 228 秒不动，歌词往前走了
      now = now.add(const Duration(seconds: 7));
      state = engine.handle(
        _push('第四句', position: const Duration(seconds: 228), playing: false),
      );

      expect(state.positionStale, isTrue, reason: '位置 7 秒没动了');
      expect(state.currentIndex, 3, reason: '按歌词认出来的就是第四句');
      expect(
        state.derivedPosition,
        const Duration(seconds: 90),
        reason: '用这一句自己的时间戳，进度条才不会永远满',
      );
      expect(state.playingNow, isTrue, reason: '歌词在走 → 就是正在播放');
    });

    test('位置正常在动 → 不做兜底，也不改口说「位置卡住」', () {
      var now = DateTime(2026, 10, 25, 14, 40);
      final sink = _FakeSink()..songs['k'] = _stored();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
        clock: () => now,
      );

      var state = engine.handle(
        _push('第一句', position: const Duration(seconds: 1)),
      );
      now = now.add(const Duration(seconds: 7));
      state = engine.handle(
        _push('第二句', position: const Duration(seconds: 32)),
      );

      expect(state.positionStale, isFalse);
      expect(state.derivedPosition, isNull, reason: '位置能用就用它');
      expect(state.currentIndex, 1);
    });

    test('暂停（歌词也停了）→ 不会误判成「位置卡住」，按钮回到播放', () {
      var now = DateTime(2026, 10, 25, 14, 40);
      final sink = _FakeSink()..songs['k'] = _stored();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
        clock: () => now,
      );

      engine.handle(_push('第二句', position: const Duration(seconds: 31)));
      // 用户暂停：位置不动、歌词也不动
      now = now.add(const Duration(seconds: 10));
      final state = engine.handle(
        _push('第二句', position: const Duration(seconds: 31), playing: false),
      );

      expect(state.positionStale, isFalse, reason: '歌词也停了，不是「卡住」');
      expect(state.playingNow, isFalse);
    });

    test('暂停几秒内歌词还没跟上 → 先当成在放（宁可晚一拍）', () {
      var now = DateTime(2026, 10, 25, 14, 40);
      final sink = _FakeSink()..songs['k'] = _stored();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
        clock: () => now,
      );

      engine.handle(_push('第二句', position: const Duration(seconds: 31)));
      now = now.add(const Duration(seconds: 2));
      final state = engine.handle(
        _push('第二句', position: const Duration(seconds: 31), playing: false),
      );

      expect(state.playingNow, isTrue, reason: '2 秒前刚有歌词，先别急着翻成暂停');
    });

    test('位置卡住时不采集：假时间戳存下来是废物', () {
      // 库里没有这首歌 → 本来会走「采集」
      final sink = _FakeSink();
      var now = DateTime(2026, 10, 25, 14, 40);
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
        clock: () => now,
      );

      engine.handle(_push('第一句', position: const Duration(seconds: 300)));
      now = now.add(const Duration(seconds: 7));
      engine.handle(_push('第二句', position: const Duration(seconds: 300)));

      expect(sink.songs.isEmpty, isTrue, reason: '每句都被记成同一个假时间戳，存了也没法回放');
    });
  });
}
