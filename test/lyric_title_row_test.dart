// ============================================================
// 「歌词写在歌曲名行」模式的测试
//
// 用例里的字段形状是**照着真机抓下来的**（BYD 车机 + 蓝牙 AVRCP，网易云/QQ 的
// 车载歌词就是这么发的）：
//   TITLE  = 当前这句歌词（每两秒一变）
//   ARTIST = "专辑 - 歌手"（全程不变）
//   ALBUM  = 专辑（全程不变）
//   DURATION = 194498（不变）
// 只有 7 个键，没有第二个地方藏着真歌名。
// ============================================================
import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';
import 'package:flutter_test/flutter_test.dart';

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

const _artist = '烂人生 - 泥鳅zinco';
const _album = '烂人生';
const _duration = Duration(milliseconds: 194498);

/// 真机上的形状：歌曲名 = 歌词，歌手/专辑/时长不变。
MediaInfo _titleLyric(
  String lyric, {
  Duration position = Duration.zero,
  Duration duration = _duration,
  bool playing = true,
}) => MediaInfo(
  hasSession: true,
  packageName: 'com.android.bluetooth',
  appLabel: '蓝牙',
  title: lyric,
  artist: _artist,
  album: _album,
  duration: duration,
  position: position,
  playing: playing,
);

void main() {
  group('歌曲名行：采集', () {
    test('歌曲名每换一句就采一句，歌手/专辑行不掺进来', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('把玩笑尽量开得每个人都懂'));
      engine.handle(
        _titleLyric('把表情收拢我装的酷得像周董', position: const Duration(seconds: 2)),
      );
      engine.handle(
        _titleLyric(
          '然后在一个人的时候打开窗让空气流通',
          position: const Duration(seconds: 5),
        ),
      );

      // 歌曲名行模式下不丢基线：那一行从头到尾都是歌词，第一眼就该收下
      expect(
        engine.state.lines.map((line) => line.text),
        ['把玩笑尽量开得每个人都懂', '把表情收拢我装的酷得像周董', '然后在一个人的时候打开窗让空气流通'],
      );
      expect(
        engine.state.lines.map((line) => line.text).contains(_artist),
        isFalse,
        reason: '歌手行不该被当成歌词',
      );
    });

    test('标题没变就不重复采', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('第一句'));
      engine.handle(
        _titleLyric('第二句', position: const Duration(seconds: 3)),
      );
      engine.handle(
        _titleLyric('第二句', position: const Duration(seconds: 4)),
      );

      expect(engine.state.lines.length, 2);
      expect(engine.state.lines.last.text, '第二句');
    });

    test('歌曲标识用歌手 + 专辑 + 时长，不用歌曲名（那是歌词）', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('第一句'));
      engine.handle(
        _titleLyric('第二句', position: const Duration(seconds: 3)),
      );

      final expected = titleRowSongKey(
        artist: _artist,
        album: _album,
        duration: _duration,
      );
      expect(engine.state.song?.key, expected);
      expect(sink.songs.keys, [expected]);
      expect(
        engine.state.song?.title,
        _artist,
        reason: '真歌名拿不到，库里就用播放器挪到歌手行的「专辑 - 歌手」',
      );
    });

    test('同一张专辑连着放下一首（歌手/专辑一样、时长不同）→ 不会并成一条', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      // 第一首（听到 40 秒，不是随手跳过的）
      engine.handle(_titleLyric('第一首的甲句'));
      engine.handle(
        _titleLyric('第一首的乙句', position: const Duration(seconds: 3)),
      );
      engine.handle(
        _titleLyric('第一首的丙句', position: const Duration(seconds: 40)),
      );
      final firstKey = engine.state.song!.key;

      // 下一首：同专辑同歌手，但时长不同
      const nextDuration = Duration(milliseconds: 231000);
      final next = engine.handle(
        _titleLyric('第二首的开头', duration: nextDuration),
      );

      expect(next.song!.key, isNot(firstKey), reason: '不同曲必须是不同记录');
      expect(next.lines.map((line) => line.text), ['第二首的开头']);
      expect(sink.songs.length, 2);
      expect(
        sink.find(firstKey)!.lines.length,
        3,
        reason: '上一首的歌词不能被搬走',
      );
    });

    test('时长是后到的（0 → 已知）→ 仍算同一首歌，只换 key 不换歌', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('甲句', duration: Duration.zero));
      engine.handle(
        _titleLyric('乙句', duration: Duration.zero, position: const Duration(seconds: 3)),
      );
      expect(engine.state.lines.length, 2);

      final known = engine.handle(
        _titleLyric('丙句', position: const Duration(seconds: 6)),
      );
      expect(known.lines.length, 3, reason: '已采的歌词要跟着搬过来');
      expect(
        known.song!.key,
        titleRowSongKey(
          artist: _artist,
          album: _album,
          duration: _duration,
        ),
      );
      expect(sink.songs.length, 1, reason: '旧的 xx|0 残档要清掉');
    });
  });

  group('「自动判断」自动识别歌曲名行', () {
    test('标题在变但播放位置一路向前 → 自动切到歌曲名行并开始采', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink); // 默认 auto

      engine.handle(_titleLyric('第一句歌词', position: Duration.zero));
      engine.handle(
        _titleLyric('第二句歌词', position: const Duration(seconds: 3)),
      );
      engine.handle(
        _titleLyric('第三句歌词', position: const Duration(seconds: 6)),
      );

      expect(engine.state.lines, isNotEmpty, reason: '应该已经认出来并开始采了');
      expect(
        engine.state.song!.key,
        titleRowSongKey(
          artist: _artist,
          album: _album,
          duration: _duration,
        ),
        reason: '认出来之后要用「歌手 + 专辑 + 时长」认歌，不能用歌曲名',
      );
    });

    test('位置带一点抖动（真机实测往回退 63ms）→ 仍然要认出来', () {
      // 真机上那两份相邻的推送：位置 181006ms → 180943ms。
      // 车机蓝牙协议栈约每秒重发一次状态，重发带的位置比上一次推算出来的值略小。
      // 第一版写成「位置必须前进 0.5 秒」就是被这 63ms 挡住的，一首都没采到。
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink); // 默认 auto

      engine.handle(
        _titleLyric('只要我不问就代表不用你回答',
            position: const Duration(milliseconds: 181006)),
      );
      engine.handle(
        _titleLyric('如果我错了那就错在玩 hiphop',
            position: const Duration(milliseconds: 180943)),
      );
      engine.handle(
        _titleLyric('想买辆代步车它就是不降价',
            position: const Duration(milliseconds: 183292)),
      );

      expect(
        engine.state.lines.map((line) => line.text),
        ['如果我错了那就错在玩 hiphop', '想买辆代步车它就是不降价'],
        reason: '这点抖动不能当成换歌',
      );
      expect(
        engine.state.song!.key,
        titleRowSongKey(artist: _artist, album: _album, duration: _duration),
      );
    });

    test('标题变了但播放位置回到开头 → 那是真的换歌，不许误判成歌词', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink); // 默认 auto

      // 第一首放到 3 分钟
      engine.handle(
        MediaInfo(
          hasSession: true,
          title: '第一首',
          artist: _artist,
          album: _album,
          duration: _duration,
          position: const Duration(minutes: 3),
          playing: true,
        ),
      );
      // 下一首：标题变了，位置回到 0 —— 典型的换歌
      final next = engine.handle(
        MediaInfo(
          hasSession: true,
          title: '第二首',
          artist: _artist,
          album: _album,
          duration: _duration,
          position: const Duration(seconds: 1),
          playing: true,
        ),
      );

      expect(next.lines, isEmpty, reason: '换歌不该被当成歌词采下来');
      expect(
        next.song!.key,
        songKey(title: '第二首', duration: _duration),
        reason: '普通播放器仍按「标题 + 时长」认歌',
      );
    });

    test('哔哩音乐那种「歌词在歌手行」的流不受影响', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink); // 默认 auto

      engine.handle(
        const MediaInfo(
          hasSession: true,
          title: '发如雪',
          artist: '周杰伦',
          album: '哔哩音乐',
          duration: Duration(minutes: 3),
          playing: true,
        ),
      );
      engine.handle(
        const MediaInfo(
          hasSession: true,
          title: '发如雪',
          artist: '♪ 狼牙月伊人憔悴 ♪',
          album: '哔哩音乐',
          duration: Duration(minutes: 3),
          position: Duration(seconds: 10),
          playing: true,
        ),
      );
      engine.handle(
        const MediaInfo(
          hasSession: true,
          title: '发如雪',
          artist: '♪ 我举杯饮尽了风雪 ♪',
          album: '哔哩音乐',
          duration: Duration(minutes: 3),
          position: Duration(seconds: 14),
          playing: true,
        ),
      );

      expect(engine.state.lines.map((line) => line.text), [
        '♪ 狼牙月伊人憔悴 ♪',
        '♪ 我举杯饮尽了风雪 ♪',
      ]);
      expect(
        engine.state.song!.key,
        songKey(title: '发如雪', duration: const Duration(minutes: 3)),
        reason: '歌手行模式仍然用「标题 + 时长」认歌',
      );
    });

    test('用户显式选了「歌曲名行」时不再需要自动识别', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );

      engine.handle(_titleLyric('第一句'));
      expect(engine.state.lines.length, 1);
      expect(engine.state.lines.single.text, '第一句');
    });
  });

  group('歌曲名行：回放与「自动判断」的边界', () {
    test('存过完整歌词 → 直接回放，不再采集', () {
      final sink = _FakeSink();
      final key = titleRowSongKey(
        artist: _artist,
        album: _album,
        duration: _duration,
      );
      sink.songs[key] = StoredSong(
        key: key,
        title: _artist,
        duration: _duration,
        complete: true,
        fromStart: true,
        lines: const [
          LyricLine(timeMs: 0, text: '甲'),
          LyricLine(timeMs: 3000, text: '乙'),
          LyricLine(timeMs: 6000, text: '丙'),
        ],
      );

      final engine = LyricCaptureEngine(
        sink: sink,
        field: LyricFieldPreference.title,
      );
      final state = engine.handle(
        _titleLyric('随便什么歌词', position: const Duration(seconds: 4)),
      );

      expect(state.mode, LyricMode.replay);
      expect(state.currentIndex, 1);
      expect(state.lines.length, 3);
    });

    test('连续跳歌（标题变了、位置回到开头）→ 一句都采不到，也不在库里留空壳', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      // 用户一首一首地跳：每首放到两三秒就切下一首，位置每次都回到开头
      for (final (name, seconds) in [('第一首', 200), ('第二首', 215), ('第三首', 187)]) {
        engine.handle(
          MediaInfo(
            hasSession: true,
            title: name,
            artist: _artist,
            album: _album,
            duration: Duration(seconds: seconds),
            position: const Duration(seconds: 2),
            playing: true,
          ),
        );
      }

      expect(engine.state.lines, isEmpty, reason: '这些都是歌名，不是歌词');
      expect(
        sink.songs,
        isEmpty,
        reason: '一句都没采到就不该往歌词库里写记录（否则切歌一快就堆一堆空壳）',
      );
    });
  });

  // ==========================================================
  // 下面这些用例的输入全部是**真机 logcat 里抄下来的原样数据**（BYD 车机 + 网易云），
  // 每一组都对应一个在车上真的踩过的坑。
  // ==========================================================
  group('真机抓到的坑：网易云把歌手行写成「歌曲名 - 歌手」', () {
    /// 真机两份相邻推送，相隔 274ms：
    ///   推送A：TITLE=Gymnopedie No. 1  ARTIST=Steve Hackett/John Hackett  pos=1916ms
    ///   推送B：TITLE=Gymnopedie No. 1  ARTIST=Gymnopedie No. 1 - Steve Hackett/…  pos=11ms
    /// 歌手行那一变不是歌词，采进来就是一句垃圾（歌名 + 歌手）。
    MediaInfo push(String artist, Duration position) => MediaInfo(
      hasSession: true,
      packageName: 'com.android.bluetooth',
      appLabel: '蓝牙',
      title: 'Gymnopedie No. 1',
      artist: artist,
      album: 'Sketches of Satie',
      duration: const Duration(seconds: 235),
      position: position,
      playing: true,
    );

    test('歌手行变成「歌曲名 - 歌手」时不能当成歌词采下来', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink); // 默认 auto

      engine.handle(push('Steve Hackett/John Hackett', const Duration(milliseconds: 1916)));
      engine.handle(
        push(
          'Gymnopedie No. 1 - Steve Hackett/John Hackett',
          const Duration(milliseconds: 11),
        ),
      );

      expect(engine.state.lines, isEmpty, reason: '那是身份信息，不是歌词');
      expect(sink.songs, isEmpty, reason: '不该为它单独建一条歌词');
    });

    test('歌词行里出现「歌曲名 - xxx」形状的句子才拦得住', () {
      expect(looksLikeTitleArtistLine('Gymnopedie No. 1 - Steve Hackett', 'Gymnopedie No. 1'), isTrue);
      expect(looksLikeTitleArtistLine('烂人生 - 泥鳅zinco', '烂人生'), isTrue);
      // 下面这些是正经句子，不能拦
      expect(looksLikeTitleArtistLine('♪ 狼牙月伊人憔悴 ♪', '发如雪'), isFalse);
      expect(looksLikeTitleArtistLine('晴天下起雨', '晴天'), isFalse);
      expect(looksLikeTitleArtistLine('作曲: Yiruma', 'River Flows in You'), isFalse);
      expect(looksLikeTitleArtistLine('Gymnopedie No. 1 主题变奏', 'Gymnopedie No. 1'), isFalse);
    });
  });

  group('真机抓到的坑：换歌那一刻的位置是上一首的僵尸值', () {
    /// 真机序列：换歌时报上来 5.5s，接着一路涨到 10.5s（还在按上一首的进度走），
    /// 然后才掉回 0.9s —— 那才是新歌自己的位置。
    /// 一开始就按 10.5s 判「不是从头听起」，这首歌会被永远误标「缺开头」。
    MediaInfo push(Duration position) => MediaInfo(
      hasSession: true,
      title: '看着岸上的人玩着规避球',
      artist: '烂人生 - 泥鳅zinco',
      album: '烂人生',
      duration: _duration,
      position: position,
      playing: true,
    );

    test('位置随后掉回开头 → 认下来：确实是从头听的', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      // 先放上一首，位置已经走到 8s
      engine.handle(
        MediaInfo(
          hasSession: true,
          title: '上一首',
          artist: '烂人生 - 泥鳅zinco',
          album: '烂人生',
          duration: _duration,
          position: const Duration(seconds: 8),
          playing: true,
        ),
      );
      // 换歌：报上来的位置还是上一首那种量级
      engine.handle(push(const Duration(milliseconds: 5500)));
      expect(engine.state.song!.fromStart, isFalse, reason: '还没定下来');

      // 僵尸值一路往前走到 10.5s
      engine.handle(push(const Duration(milliseconds: 10500)));
      expect(engine.state.song!.fromStart, isFalse);

      // 掉回开头 —— 现在能确认了
      final settled = engine.handle(push(const Duration(milliseconds: 900)));
      expect(settled.song!.fromStart, isTrue, reason: '其实是从头听起的，别误标缺开头');
      expect(sink.songs[settled.song!.key]!.fromStart, isTrue, reason: '要落到库里');
    });

    test('位置一路往前跑过 20 秒 → 那是真的从半路插进来的，保持「缺开头」', () {
      final sink = _FakeSink();
      final engine = LyricCaptureEngine(sink: sink);

      engine.handle(
        MediaInfo(
          hasSession: true,
          title: '上一首',
          artist: '烂人生 - 泥鳅zinco',
          album: '烂人生',
          duration: _duration,
          position: const Duration(seconds: 8),
          playing: true,
        ),
      );
      engine.handle(push(const Duration(milliseconds: 5500)));
      engine.handle(push(const Duration(seconds: 25)));
      engine.handle(push(const Duration(seconds: 40)));

      expect(engine.state.song!.fromStart, isFalse);
      expect(engine.state.song!.missingStart, isTrue);
    });
  });
}
