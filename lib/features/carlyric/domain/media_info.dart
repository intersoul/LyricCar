// ============================================================
// 系统媒体会话里读到的「正在播放」信息
//
// 数据来源是原生侧的 MediaSessionBridge（NotificationListenerService +
// MediaSessionManager + MediaController），字段名与 MediaMetadata 的
// METADATA_KEY_* 一一对应：
//   METADATA_KEY_TITLE  / ARTIST / ALBUM / DURATION
// 额外带上来源包名（用来显示「网易云音乐」这类来源标签）与播放位置。
// ============================================================
import 'dart:typed_data';

class MediaInfo {
  const MediaInfo({
    this.hasSession = false,
    this.sessionCount = 0,
    this.packageName = '',
    this.appLabel = '',
    this.title = '',
    this.artist = '',
    this.album = '',
    this.duration = Duration.zero,
    this.position = Duration.zero,
    this.playing = false,
    this.artUri = '',
    this.artBytes,
  });

  /// 当前有没有可用的媒体会话（没有就是车机上没有 App 在播歌）。
  final bool hasSession;

  /// 系统里一共有几个媒体会话（用于诊断：车机蓝牙播放器算不算一个）。
  final int sessionCount;

  final String packageName;
  final String appLabel;

  final String title;
  final String artist;
  final String album;
  final Duration duration;
  final Duration position;
  final bool playing;

  /// 封面 URI（`content://...`，Flutter 侧加载不了，仅作缓存键用）。
  final String artUri;

  /// 封面已解码好的 JPEG 字节（原生侧读出来并压过），可能为 null。
  final Uint8List? artBytes;

  bool get hasTrack => hasSession && title.trim().isNotEmpty;

  MediaInfo copyWith({Uint8List? artBytes, Duration? position}) => MediaInfo(
    hasSession: hasSession,
    sessionCount: sessionCount,
    packageName: packageName,
    appLabel: appLabel,
    title: title,
    artist: artist,
    album: album,
    duration: duration,
    // 注意：不传就是保持原值（位置要是被顺手置空，界面会以为回到开头）
    position: position ?? this.position,
    playing: playing,
    artUri: artUri,
    artBytes: artBytes ?? this.artBytes,
  );

  static const MediaInfo none = MediaInfo();

  factory MediaInfo.fromMap(Map<Object?, Object?> map) {
    return MediaInfo(
      hasSession: map['hasSession'] == true,
      sessionCount: _int(map['sessionCount']),
      packageName: '${map['packageName'] ?? ''}',
      appLabel: '${map['appLabel'] ?? ''}',
      title: '${map['title'] ?? ''}',
      artist: '${map['artist'] ?? ''}',
      album: '${map['album'] ?? ''}',
      duration: Duration(milliseconds: _int(map['durationMs'])),
      position: Duration(milliseconds: _int(map['positionMs'])),
      playing: map['playing'] == true,
      artUri: '${map['artUri'] ?? ''}',
    );
  }

  static int _int(Object? value) => value is num ? value.round() : 0;

  @override
  bool operator ==(Object other) =>
      other is MediaInfo &&
      other.hasSession == hasSession &&
      other.sessionCount == sessionCount &&
      other.packageName == packageName &&
      other.appLabel == appLabel &&
      other.title == title &&
      other.artist == artist &&
      other.album == album &&
      other.duration == duration &&
      other.position == position &&
      other.playing == playing &&
      other.artUri == artUri;

  @override
  int get hashCode => Object.hash(
    hasSession,
    sessionCount,
    packageName,
    appLabel,
    title,
    artist,
    album,
    duration,
    position,
    playing,
    artUri,
  );

  @override
  String toString() =>
      'MediaInfo($appLabel "$title" - "$artist" [${position.inSeconds}s/'
      '${duration.inSeconds}s] playing=$playing)';
}
