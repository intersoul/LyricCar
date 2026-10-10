import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/song_key.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('lyricIndexAt', () {
    const lyrics = [
      LyricLine(timeMs: 0, text: '一'),
      LyricLine(timeMs: 5000, text: '二'),
      LyricLine(timeMs: 10000, text: '三'),
    ];

    test('空歌词返回 -1', () {
      expect(lyricIndexAt(const [], const Duration(seconds: 3)), -1);
    });

    test('还没到第一句返回 -1', () {
      expect(lyricIndexAt(lyrics, const Duration(milliseconds: -1)), -1);
    });

    test('正好卡在时间点上是那一句', () {
      expect(lyricIndexAt(lyrics, Duration.zero), 0);
      expect(lyricIndexAt(lyrics, const Duration(seconds: 5)), 1);
      expect(lyricIndexAt(lyrics, const Duration(seconds: 10)), 2);
    });

    test('两句之间取前一句', () {
      expect(lyricIndexAt(lyrics, const Duration(milliseconds: 4999)), 0);
      expect(lyricIndexAt(lyrics, const Duration(milliseconds: 9999)), 1);
    });

    test('放到最后一句之后仍是最后一句', () {
      expect(lyricIndexAt(lyrics, const Duration(minutes: 5)), 2);
    });

    test('大量歌词也能算对（二分边界）', () {
      final many = [
        for (var i = 0; i < 1000; i++) LyricLine(timeMs: i * 1000, text: '$i'),
      ];
      expect(lyricIndexAt(many, Duration.zero), 0);
      expect(lyricIndexAt(many, const Duration(milliseconds: 999_000)), 999);
      expect(lyricIndexAt(many, const Duration(milliseconds: 123_456)), 123);
    });
  });

  group('songKey', () {
    test('同一首歌的不同大小写 / 空格归一化到同一个 key', () {
      expect(
        songKey(
          title: '  Hello   World ',
          duration: const Duration(seconds: 200),
        ),
        songKey(title: 'hello world', duration: const Duration(seconds: 200)),
      );
    });

    test('时长在 ±2.5 秒内归到同一个桶', () {
      expect(
        songKey(title: '歌', duration: const Duration(seconds: 213)),
        songKey(title: '歌', duration: const Duration(seconds: 216)),
      );
    });

    test('时长差得多就是两首歌（同名不同版本）', () {
      expect(
        songKey(title: '歌', duration: const Duration(seconds: 200)),
        isNot(songKey(title: '歌', duration: const Duration(seconds: 240))),
      );
    });

    test('时长未知时用 0 桶', () {
      expect(songKey(title: '歌', duration: Duration.zero), '歌|0');
      expect(songKey(title: '歌', duration: const Duration(seconds: -3)), '歌|0');
    });
  });

  group('LyricLine JSON', () {
    test('往返一致', () {
      const line = LyricLine(timeMs: 12345, text: '一句歌词');
      final restored = LyricLine.fromJson(line.toJson());
      expect(restored?.timeMs, 12345);
      expect(restored?.text, '一句歌词');
    });

    test('脏数据返回 null', () {
      expect(LyricLine.fromJson(null), isNull);
      expect(LyricLine.fromJson({'t': 0}), isNull);
      expect(LyricLine.fromJson({'t': 0, 'text': '  '}), isNull);
      expect(LyricLine.fromJson({'t': 'abc', 'text': '歌词'}), isNull);
    });

    test('时间支持数字字符串（手改过的导出文件）', () {
      expect(LyricLine.fromJson({'t': '1500', 'text': '歌词'})?.timeMs, 1500);
    });
  });
}
