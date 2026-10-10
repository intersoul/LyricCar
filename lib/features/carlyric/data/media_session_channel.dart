// ============================================================
// 原生「系统媒体会话」桥的 Dart 侧
//
// 读别的 App（网易云 / QQ 音乐 / 车机自带蓝牙播放器）写进 MediaSession 的
// 标题 / 歌手 / 专辑 / 时长 / 播放位置，Dart 自己做不到，必须落到原生：
//   android/app/src/main/kotlin/com/homeweb/lyriccar/MediaSessionBridge.kt
//
// 通道约定：
//   EventChannel  carlyric/media_stream  →  每次状态变化推一份 Map（见 MediaInfo.fromMap）
//   MethodChannel carlyric/media         →  权限查询 / 跳设置 / 播放控制
//
// 封面单独说一句：MediaSession 里的封面是 `content://` URI 或 Bitmap，Flutter
// 的 Image.network 读不了。所以原生侧把封面解码成 JPEG、压到合适尺寸后以
// base64 只在**封面变化时**随事件带下来，这里按 artUri 缓存住。
// ============================================================
import 'dart:convert';

import 'package:flutter/services.dart';

import '../domain/media_info.dart';

class MediaSessionChannel {
  MediaSessionChannel({
    this._method = const MethodChannel(_methodName),
    this._events = const EventChannel(_eventName),
  });

  static const String _methodName = 'carlyric/media';
  static const String _eventName = 'carlyric/media_stream';

  final MethodChannel _method;
  final EventChannel _events;

  /// 订阅系统媒体会话。原生侧在订阅时立刻回推一次当前状态。
  Stream<MediaInfo> watch() {
    var artUri = '';
    Uint8List? art;
    final clock = MonotonicPlaybackClock();

    return _events.receiveBroadcastStream().map((event) {
      final map = (event as Map).cast<Object?, Object?>();
      final raw = MediaInfo.fromMap(map);
      final info = clock.smooth(raw);

      // 换歌 / 换封面 → 旧封面作废，避免上一首的图配这一首的歌名
      if (info.artUri != artUri) {
        artUri = info.artUri;
        art = null;
      }
      final encoded = map['artBase64'];
      if (encoded is String && encoded.isNotEmpty) {
        try {
          art = base64Decode(encoded);
        } catch (_) {
          art = null;
        }
      }
      return info.copyWith(artBytes: art);
    });
  }

  /// 有没有「通知使用权」。没有的话读不到别的 App 的媒体会话，必须先引导用户去开。
  Future<bool> hasNotificationAccess() async {
    try {
      return await _method.invokeMethod<bool>('hasNotificationAccess') ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// 本应用的通知监听组件名（`包名/服务类名`）。
  ///
  /// 车机上像 BYD 这样的 ROM 会把「通知使用权」设置页整个删掉（点进去只弹
  /// 「不支持该操作」），这时只能靠电脑跑一条 adb —— 所以要把确切组件名告诉用户。
  Future<String> listenerComponent() async {
    try {
      return await _method.invokeMethod<String>('listenerComponent') ?? '';
    } on PlatformException {
      return '';
    } on MissingPluginException {
      return '';
    }
  }

  /// 跳到「通知使用权」系统设置页。
  Future<void> openNotificationAccessSettings() async {
    try {
      await _method.invokeMethod<void>('openNotificationAccessSettings');
    } on PlatformException {
      // 打不开就算了，界面上还有手动路径提示
    } on MissingPluginException {
      // 非 Android（比如跑在桌面上调试）时静默忽略
    }
  }

  // ---- 播放控制：MediaController.transportControls，直接作用在别人的播放器上 ----
  Future<void> play() => _invoke('play');

  Future<void> pause() => _invoke('pause');

  Future<void> next() => _invoke('next');

  Future<void> previous() => _invoke('previous');

  Future<void> seek(Duration position) =>
      _invoke('seek', {'positionMs': position.inMilliseconds});

  Future<void> _invoke(String method, [Map<String, Object?>? args]) async {
    try {
      await _method.invokeMethod<void>(method, args);
    } on PlatformException {
      // 目标播放器可能不支持某个动作，忽略即可
    } on MissingPluginException {
      // 同上
    }
  }
}

/// 把「播放位置」这个会来回跳的时钟压平。
///
/// 原生侧的位置是「原始位置 + 经过时间 × 倍速」外推出来的，而车机蓝牙协议栈大约
/// 每秒重推一次状态，重推带回来的原始位置常常比刚才外推出来的值**小**几百毫秒 ——
/// 于是位置像个来回摆的钟。真机实测（放歌词时抓的）：
///
/// ```
/// 39269ms → 38516ms → 39483ms      // 换句那一瞬间先退 753ms，再前进去
/// ```
///
/// 后果就是界面上「当前第几句」先前进、再退回上一句、又前进 —— 屏幕上看就是歌词
/// **抽搐一下**（车主反馈）。位置在物理上只会往前走，所以这里把「小幅度回退」
/// 压平：回退不到 [seekTolerance] 的，报上一次的值；回退超过它的照认（那是真的
/// 在拖进度条）。
class MonotonicPlaybackClock {
  /// 回退多少以内算抖动。真机抖动约 0.5~0.8 秒；真人拖动进度条不会只拖两秒。
  static const Duration seekTolerance = Duration(seconds: 2);

  /// 「这就是开头」的门槛：位置不到 1 秒（采集引擎判「这一遍是从头听起的」用的
  /// 也是这个数字，见 `LyricCaptureEngine._resolveStartUndecided`）。
  ///
  /// 掉回开头永远是**重新放**，不是抖动，就算只退了半秒也要照实报出去 ——
  /// 下游有好几条规则都盯着「位置掉回开头」这个信号（认是从头听起、重放补词）。
  /// 把它们压平的代价，比一秒之内的显示精度贵得多。
  static const Duration restartCeiling = Duration(seconds: 1);

  Duration? _last;

  MediaInfo smooth(MediaInfo info) {
    if (!info.hasTrack) {
      _last = null;
      return info;
    }
    final previous = _last;
    final position = info.position;
    if (previous != null && _isJitter(previous, position)) {
      // 抖回去的：报上一次的值（对外看起来就是个单调钟）
      return info.copyWith(position: previous);
    }
    _last = position;
    return info;
  }

  bool _isJitter(Duration previous, Duration position) {
    if (position >= previous) return false;
    // 掉回开头是「重新放 / 从头听」，不是抖动，必须让下游看见
    if (position < restartCeiling && previous >= restartCeiling) return false;
    return previous - position < seekTolerance;
  }
}
