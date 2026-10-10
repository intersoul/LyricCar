// ============================================================
// 歌曲标识
//
// 用来把「采集到的歌词」和「某一首歌」对上号，下次再放时才知道该取哪份。
//
// ⚠️ 为什么不用歌手 / 专辑：车载歌词的通用做法正是把**歌手行或专辑行**
// 改成当前这句歌词（见 README），这两行在播放过程中一直在变，做不了主键。
// 只有**标题**是全程稳定的，所以用「标题 + 时长桶」兜住同名不同曲的情况。
// ============================================================

/// 归一化标题：去首尾空白、压连续空白、转小写。
String normalizeTitle(String title) =>
    title.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');

/// 「歌词写在歌曲名行」时用的歌曲标识。
///
/// 那行本身（歌曲名）就是歌词，一直在变，当不了主键；只能用**歌手 + 专辑 + 时长**：
/// 实测网易云 / QQ 音乐在歌曲名行放歌词时，会把身份信息挪到歌手行（格式是
/// `专辑 - 歌手`），专辑行也保持真实，所以这两行是稳的。
///
/// 时长这里用**精确到秒**而不是 5 秒分桶：同一张专辑连着放两首时歌手 / 专辑都
/// 一样，时长是唯一的分辨依据，桶太粗会把两首歌并成一条。
String titleRowSongKey({
  required String artist,
  required String album,
  required Duration duration,
}) {
  final seconds = duration.inSeconds;
  final bucket = seconds <= 0 ? 0 : seconds;
  return '${normalizeTitle(artist)}\u0000${normalizeTitle(album)}|$bucket';
}

/// 「歌手行」在有些播放器上写的不是歌手，而是 `歌曲名 - 歌手` 这种身份信息
/// —— 网易云就是这么发的：`TITLE=当前这句歌词`、`ARTIST=歌曲名 - 歌手`。
/// 换歌时这一行会跟着变一次，那不是歌词，采进来就是一句垃圾（真机抓到的）。
///
/// 判据要求「歌曲名」后面紧跟一个连字符，正常歌词句很难长这样。
bool looksLikeTitleArtistLine(String text, String title) {
  final head = normalizeTitle(title);
  if (head.isEmpty) return false;
  final value = normalizeTitle(text);
  if (!value.startsWith(head)) return false;
  final rest = value.substring(head.length).trimLeft();
  return rest.startsWith('-') || rest.startsWith('–') || rest.startsWith('—');
}

/// 由「标题 + 时长」生成稳定 key。
///
/// 时长按 5 秒分桶（±2.5s 归为同一个桶），因为不同播放器/不同版本报出来的
/// 时长常常差个一两秒；时长未知（<=0）时归到 0 桶。
String songKey({required String title, required Duration duration}) {
  final seconds = duration.inSeconds;
  final bucket = seconds <= 0 ? 0 : (seconds / 5).round() * 5;
  return '${normalizeTitle(title)}|$bucket';
}
