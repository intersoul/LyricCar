import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('mergeLyricLine', () {
    test('新句子按时间插到正确位置', () {
      final lines = [
        const LyricLine(timeMs: 0, text: 'A'),
        const LyricLine(timeMs: 10000, text: 'B'),
      ];
      final merged = mergeLyricLine(
        lines,
        const LyricLine(timeMs: 5000, text: 'C'),
      );
      expect(merged.map((line) => line.text), ['A', 'C', 'B']);
    });

    test('重听一遍时，同一句（文本相同、时间几乎一样）不会重复入库', () {
      final firstPass = [
        for (var i = 0; i < 5; i++)
          LyricLine(timeMs: i * 5000, text: '第 $i 句'),
      ];
      var lines = List.of(firstPass);

      // 第二遍：同样的音频，元数据推送延迟抖了几百毫秒
      for (var i = 0; i < 5; i++) {
        lines = mergeLyricLine(
          lines,
          LyricLine(timeMs: i * 5000 + 400, text: '第 $i 句'),
        );
      }
      expect(lines.length, 5, reason: '重听不能把整首歌复制一份');
      expect(lines.first.timeMs, 0, reason: '保留先采到的那份时间戳');
    });

    test('副歌在后面再唱一遍仍然各记一次（文本相同但时间差得远）', () {
      var lines = <LyricLine>[];
      lines = mergeLyricLine(lines, const LyricLine(timeMs: 5000, text: '副歌'));
      lines = mergeLyricLine(lines, const LyricLine(timeMs: 6000, text: '主歌'));
      lines = mergeLyricLine(
        lines,
        const LyricLine(timeMs: 90000, text: '副歌'),
      );
      expect(lines.map((line) => line.text), ['副歌', '主歌', '副歌']);
    });

    test('时间挨得很近但文本不同，不会被误判成同一句', () {
      var lines = <LyricLine>[];
      lines = mergeLyricLine(lines, const LyricLine(timeMs: 10000, text: 'A'));
      lines = mergeLyricLine(lines, const LyricLine(timeMs: 11500, text: 'B'));
      lines = mergeLyricLine(lines, const LyricLine(timeMs: 12500, text: 'C'));
      expect(lines.length, 3);
    });

    test('空列表直接收下第一句', () {
      final merged = mergeLyricLine(
        const [],
        const LyricLine(timeMs: 1234, text: '第一句'),
      );
      expect(merged.single.text, '第一句');
    });
  });
}
