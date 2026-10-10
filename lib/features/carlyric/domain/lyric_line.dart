// ============================================================
// 歌词行 —— 车机歌词显示的最小数据单元
//
// 这套模型同时服务两种场景：
//   * **采集**：从系统媒体会话（MediaSession）里一句一句抓到歌词，
//     抓到时把「播放器当前报的位置」记成这句的时间戳；
//   * **回放**：下次再放这首歌时，直接用存下来的时间戳做同步滚动。
// 两边共用同一个时间戳定义，所以采集到的歌词天然就是同步好的。
// ============================================================

/// 一句歌词。
class LyricLine {
  const LyricLine({required this.timeMs, required this.text});

  /// 这句歌词出现时的播放位置（毫秒）。
  final int timeMs;

  final String text;

  Duration get time => Duration(milliseconds: timeMs);

  Map<String, Object?> toJson() => {'t': timeMs, 'text': text};

  /// 容错解析：脏数据（时间不是数字 / 文本为空）返回 null 由调用方丢弃。
  static LyricLine? fromJson(Object? json) {
    if (json is! Map) return null;
    final raw = json['t'];
    final text = json['text']?.toString().trim() ?? '';
    if (text.isEmpty) return null;
    final ms = raw is num ? raw.round() : int.tryParse('$raw');
    if (ms == null) return null;
    return LyricLine(timeMs: ms, text: text);
  }

  @override
  String toString() => 'LyricLine($timeMs, $text)';
}

/// 播放到 [position] 时应该高亮第几句歌词；返回 -1 表示还没到第一句。
///
/// 纯函数（domain 层），因为有三个使用方：界面高亮、自动滚动定位、
/// 以及「采集时判断这句是不是新的」。歌词动辄几百句，二分查找只看 log(n)。
int lyricIndexAt(List<LyricLine> lyrics, Duration position) {
  if (lyrics.isEmpty) return -1;

  final ms = position.inMilliseconds;
  if (ms < lyrics.first.timeMs) return -1;

  var low = 0;
  var high = lyrics.length - 1;
  while (low < high) {
    final mid = (low + high + 1) >> 1;
    if (lyrics[mid].timeMs <= ms) {
      low = mid;
    } else {
      high = mid - 1;
    }
  }
  return low;
}

/// 判定「同一句重采」的时间容差。
///
/// 1.5 秒足够吸收两次播放之间元数据推送延迟的抖动，又明显小于常见歌词行的
/// 间距，所以不会误伤「同一句连唱两遍」（那种情况两句通常隔十几秒）。
const int kLyricMergeWindowMs = 1500;

/// 把一句新采到的歌词并进 [lines]，返回按时间升序的新列表。
///
/// 判重规则：**时间和文本都对得上**已有某句时，视为同一句的重采，丢掉新的。
/// 这条规则是为「一首只采了一半的歌，下次再听」准备的 —— 新一遍会把已经采过
/// 的那段原样再送一遍，不判重就会把歌词整段复制成两份。
///
/// 文本必须相同才判重，所以副歌在后面再唱一遍（文本相同但时间差得远）仍会各记一次。
List<LyricLine> mergeLyricLine(List<LyricLine> lines, LyricLine incoming) {
  for (final line in lines) {
    if (line.text != incoming.text) continue;
    if ((line.timeMs - incoming.timeMs).abs() <= kLyricMergeWindowMs) {
      return lines;
    }
  }
  final merged = [...lines, incoming];
  merged.sort((a, b) => a.timeMs.compareTo(b.timeMs));
  return merged;
}
