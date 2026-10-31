// ============================================================
// 「先按歌名找歌词，找不到再退回换歌后那套 key」
//
// 车主的原话：大部分歌的歌名都不一样，所以先按歌名匹配；重名或者对不上，
// 再用「歌词把歌名行占掉之后」那套（歌手 + 专辑 + 时长）找一遍。
//
// 为什么这么做：后者带着**播放器报的时长**，同一首歌报 3120 / 3125 秒就会变成
// 两个 key，白采一遍（真机抓到过）。歌名不受这个影响 —— 但它会重名，所以还得有回退。
// ============================================================
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
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

/// 歌曲名行模式的真实形状：标题 = 歌词，歌手行 = `歌曲名 - 歌手`。
MediaInfo _titleLyric(
  String lyric, {
  String artist = '夜曲 - 周杰伦',
  String album = '十一月的肖邦',
  Duration duration = const Duration(minutes: 5),
  Duration position = Duration.zero,
}) => MediaInfo(
  hasSession: true,
  packageName: 'com.android.bluetooth',
  title: lyric,
  artist: artist,
  album: album,
  duration: duration,
  position: position,
  playing: true,
);

StoredSong _stored({
  required String key,
  required String title,
  String songName = '',
  Duration duration = const Duration(minutes: 5),
  bool complete = true,
  bool fromStart = true,
  required List<(int, String)> lines,
}) => StoredSong(
  key: key,
  title: title,
  songName: songName,
  duration: duration,
  complete: complete,
  fromStart: fromStart,
  lines: [
    for (final (second, text) in lines)
      LyricLine(timeMs: second * 1000, text: text),
  ],
);

void main() {
  group('按歌名匹配', () {
    test('歌名对得上就回放 —— 时长桶抖了也不影响（旧行为会另存一份）', () {
      final sink = _FakeSink();
      // 真机抓到的形状：同一首报 3120 / 3125 秒，key 会不一样
      final stored = _stored(
        key: titleRowSongKey(
          artist: '夜曲 - 周杰伦',
          album: '十一月的肖邦',
          duration: const Duration(seconds: 3120),
        ),
        title: '夜曲 - 周杰伦',
        songName: '夜曲',
        duration: const Duration(seconds: 3120),
        lines: const [(0, '一群嗜血的蚂蚁'), (60, '我面无表情')],
      );
      sink.songs[stored.key] = stored;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.auto,
      );
      // 真机上是两次推送：第一眼标题已经是歌词，第二眼才认得出来
      engine.handle(
        _titleLyric(
          '一群嗜血的蚂蚁',
          duration: const Duration(seconds: 3125),
          position: const Duration(seconds: 1),
        ),
      );
      final state = engine.handle(
        _titleLyric(
          '我面无表情',
          duration: const Duration(seconds: 3125),
          position: const Duration(seconds: 61),
        ),
      );

      expect(state.mode, LyricMode.replay, reason: '按歌名找到了就直接回放');
      expect(state.song!.key, stored.key, reason: '用的是稿子自己的 key，不另存一份');
      expect(sink.songs.length, 1, reason: '不能因为时长抖了 5 秒就多出一份');
    });

    test('老稿子没有 songName 字段 → 从「歌曲名 - 歌手」标题里推，一样能匹配', () {
      final sink = _FakeSink();
      final stored = _stored(
        key: 'old-record',
        title: 'River Flows in You (在你身上流动的河) - Yiruma',
        lines: const [(0, '第一句'), (30, '第二句')],
      );
      sink.songs[stored.key] = stored;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );
      final state = engine.handle(
        _titleLyric(
          '第一句',
          artist: 'River Flows in You (在你身上流动的河) - Yiruma',
          album: 'River Flows in You',
          position: const Duration(seconds: 1),
        ),
      );

      expect(state.mode, LyricMode.replay);
      expect(state.song!.key, 'old-record');
    });

    test('重名 → 挑时长最接近的那一首', () {
      final sink = _FakeSink();
      final short = _stored(
        key: 'k-short',
        title: '不想 - 甲',
        songName: '不想',
        duration: const Duration(seconds: 180),
        lines: const [(0, '短版'), (30, '短版二')],
      );
      final long = _stored(
        key: 'k-long',
        title: '不想 - 乙',
        songName: '不想',
        duration: const Duration(seconds: 280),
        lines: const [(0, '长版'), (30, '长版二')],
      );
      sink.songs[short.key] = short;
      sink.songs[long.key] = long;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );
      final state = engine.handle(
        _titleLyric(
          '长版',
          artist: '不想 - 乙',
          album: '别的专辑',
          duration: const Duration(seconds: 275),
          position: const Duration(seconds: 1),
        ),
      );

      expect(state.song!.key, 'k-long', reason: '时长 275 秒更接近 280 秒那首');
    });

    test('重名又分不清（时长一样近）→ 不瞎猜，按 key 找不到就新采一份', () {
      final sink = _FakeSink();
      for (final (key, seconds) in [('k1', 200), ('k2', 202)]) {
        final song = _stored(
          key: key,
          title: '不想 - $key',
          songName: '不想',
          duration: Duration(seconds: seconds),
          lines: [(0, '$key 甲'), (30, '$key 乙')],
        );
        sink.songs[song.key] = song;
      }

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );
      final state = engine.handle(
        _titleLyric(
          '新的一句',
          artist: '不想 - 丙',
          album: '第三张',
          duration: const Duration(seconds: 201),
        ),
      );

      expect(state.mode, LyricMode.capturing, reason: '分不清就别猜，宁可重采');
      expect(sink.songs.length, 3, reason: '新开一份，原来两份不动');
    });
  });

  group('名字匹配错了要能纠回来', () {
    /// 一份「名字对上了但其实是别的歌」的稿子 + 一份「换歌后那套 key」对应的真稿子。
    ({_FakeSink sink, StoredSong wrong, StoredSong right}) setup() {
      final sink = _FakeSink();
      final wrong = _stored(
        key: 'k-wrong',
        title: '夜曲 - 别人的版本',
        songName: '夜曲',
        duration: const Duration(seconds: 300),
        lines: const [(0, '错的第一句'), (3, '错的第二句'), (6, '错的第三句')],
      );
      final right = _stored(
        key: titleRowSongKey(
          artist: '夜曲 - 周杰伦',
          album: '十一月的肖邦',
          duration: const Duration(seconds: 300),
        ),
        // 这份是按「换歌后那套 key」存的，名字跟「夜曲」对不上 ——
        // 不然它自己也会先被名字匹配挑走，测不到回退
        title: '一份老稿子',
        duration: const Duration(seconds: 300),
        lines: const [(0, '对的第一句'), (3, '对的第二句'), (6, '对的第三句')],
      );
      sink.songs[wrong.key] = wrong;
      sink.songs[right.key] = right;
      return (sink: sink, wrong: wrong, right: right);
    }

    test('歌词对不上两次 → 改按「换歌后那套 key」回放，不写脏', () {
      final s = setup();
      final engine = LyricCaptureEngine(
        sink: s.sink,
        field: LyricFieldPreference.title,
      );

      var state = engine.handle(
        _titleLyric('对的第一句', position: const Duration(seconds: 0)),
      );
      expect(state.song!.key, s.wrong.key, reason: '先乐观地按名字匹配');

      state = engine.handle(
        _titleLyric('对的第二句', position: const Duration(seconds: 3)),
      );
      expect(state.song!.key, s.right.key, reason: '两次都对不上 → 换回真稿子');
      expect(state.mode, LyricMode.replay);

      // 错的稿子一个字都没被改
      final wrong = s.sink.find(s.wrong.key)!;
      expect(wrong.lines.map((line) => line.text), ['错的第一句', '错的第二句', '错的第三句']);
    });

    test('对上一句之后就信任它，不再逐句疑神疑鬼', () {
      final s = setup();
      final engine = LyricCaptureEngine(
        sink: s.sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('错的第一句', position: const Duration(seconds: 0)));
      final state = engine.handle(
        _titleLyric('完全不同的句子', position: const Duration(seconds: 3)),
      );
      expect(state.song!.key, s.wrong.key, reason: '第一句已经对上了，别乱切');
      expect(state.mode, LyricMode.replay);
    });

    test('歌词对得上 → 老老实实用名字匹配到的那份', () {
      final s = setup();
      final engine = LyricCaptureEngine(
        sink: s.sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('错的第一句', position: const Duration(seconds: 0)));
      engine.handle(_titleLyric('错的第二句', position: const Duration(seconds: 3)));
      final state = engine.handle(
        _titleLyric('错的第三句', position: const Duration(seconds: 6)),
      );

      expect(state.song!.key, s.wrong.key);
      expect(state.mode, LyricMode.replay);
    });

    test('同一句歌词在稿子里差得太远 → 也算对不上（时间偏差）', () {
      final sink = _FakeSink();
      final stored = _stored(
        key: 'k-far',
        title: '夜曲 - 别人',
        songName: '夜曲',
        lines: const [(0, '开头那句'), (30, '中间那句'), (60, '遥远的同一句')],
      );
      sink.songs[stored.key] = stored;

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );
      // 这两句在稿子里都「差了 40 秒以上」，位置附近只有别的句子
      engine.handle(
        _titleLyric('遥远的同一句', position: const Duration(seconds: 5)),
      );
      final state = engine.handle(
        _titleLyric('另一句也不在稿子里', position: const Duration(seconds: 12)),
      );

      expect(state.mode, LyricMode.capturing, reason: '两句都在错的位置 → 否定这次匹配，改采新稿');
    });
  });

  group('切歌那一瞬间要把歌名抓住', () {
    test('歌手行带「歌曲名 - 歌手」→ 当场就把歌名记下来，第一句歌词不丢', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      // 切歌：标题立刻就是第一句歌词（这两个字段是同一次推送里来的）
      final state = engine.handle(
        _titleLyric('把玩笑尽量开得每个人都懂', position: const Duration(seconds: 1)),
      );
      engine.handle(_titleLyric('把表情收拢', position: const Duration(seconds: 3)));

      expect(state.lines.first.text, '把玩笑尽量开得每个人都懂', reason: '第一句要收下');
      expect(engine.state.song!.songName, '夜曲', reason: '歌名从歌手行当场切出来');
    });

    test('歌手行没有「歌曲名 - 歌手」→ 切歌后第一眼标题当真歌名，不写进歌词', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      // 有的播放器先推真歌名，过一会儿才换成歌词
      engine.handle(
        _titleLyric(
          '真歌名在这',
          artist: '周杰伦',
          position: const Duration(seconds: 1),
        ),
      );
      final state = engine.handle(
        _titleLyric(
          '第一句歌词',
          artist: '周杰伦',
          position: const Duration(seconds: 3),
        ),
      );

      expect(state.lines.map((line) => line.text), ['第一句歌词'], reason: '歌名不是歌词');
      expect(state.song!.songName, '真歌名在这');
    });

    test('歌手行模式的歌名就是标题（歌词在歌手行时）', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.artist,
      );

      engine.handle(
        MediaInfo(
          hasSession: true,
          title: '可爱女人',
          artist: '真歌手',
          duration: const Duration(minutes: 4),
          playing: true,
        ),
      );
      engine.handle(
        MediaInfo(
          hasSession: true,
          title: '可爱女人',
          artist: '♪ 想要有直升机 ♪',
          duration: const Duration(minutes: 4),
          position: const Duration(seconds: 5),
          playing: true,
        ),
      );

      expect(engine.state.song!.songName, '可爱女人');
      expect(engine.state.song!.matchName, '可爱女人');
    });
  });

  group('歌名比对规则', () {
    test('「歌曲名 - 歌手」按第一个分隔符切，歌名里的括号不影响', () {
      expect(
        songNameFromTitleArtistLine('好心分手 (DUET WITH 王力宏) - 卢巧音/王力宏'),
        '好心分手 (DUET WITH 王力宏)',
      );
      expect(songNameFromTitleArtistLine('烂人生 - 泥鳅zinco'), '烂人生');
      expect(songNameFromTitleArtistLine('没有分隔符'), isNull);
      expect(songNameFromTitleArtistLine(' - 只有歌手'), isNull);
      expect(songNameFromTitleArtistLine(''), isNull);
    });

    test('matchName：有 songName 用 songName，没有就从标题里推', () {
      final withName = _stored(
        key: 'a',
        title: '烂人生 - 泥鳅zinco',
        songName: '烂人生',
        lines: const [(0, 'x'), (1, 'y')],
      );
      final without = _stored(
        key: 'b',
        title: '烂人生 - 泥鳅zinco',
        lines: const [(0, 'x'), (1, 'y')],
      );
      expect(withName.matchName, '烂人生');
      expect(without.matchName, '烂人生');
    });
  });
}
