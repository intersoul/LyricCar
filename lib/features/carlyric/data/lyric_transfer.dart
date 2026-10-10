// ============================================================
// 歌词库的导出 / 导入（走系统「下载」目录）
//
// 换车机时：老车机「导出到下载」→ 把 json 拷到新车机（U 盘 / 文件管理器都行）
// → 新车机「从下载导入」，歌词库直接续上。同一个局域网里更省事，见
// `lyric_lan_server.dart`。
//
// 这里是原生侧（LyricTransferChannel）的客户端：Android 9 及以下的公共目录
// 需要 WRITE_EXTERNAL_STORAGE 权限，Android 10 起走 MediaStore。
//
// 为什么不用系统的「另存为」对话框：这台 BYD 车机压根没有能处理
// ACTION_CREATE_DOCUMENT 的 Activity（实测 resolve-activity 返回 no activity），
// 走 SAF 必然失败 —— 这正是之前「导出失败」的原因。
// ============================================================
import 'package:flutter/services.dart';

/// 下载目录里的一个文件。
class DownloadedFile {
  const DownloadedFile({
    required this.name,
    required this.path,
    required this.size,
    required this.modifiedMs,
  });

  final String name;

  /// 文件绝对路径（Android 10 起是 content:// URI，原样传回给 [LyricTransfer.read]）。
  final String path;
  final int size;
  final int modifiedMs;

  DateTime get modifiedAt => DateTime.fromMillisecondsSinceEpoch(modifiedMs);

  static DownloadedFile fromMap(Object? raw) {
    final map = (raw as Map).cast<String, Object?>();
    return DownloadedFile(
      name: '${map['name']}',
      path: '${map['path']}',
      size: (map['size'] as num?)?.toInt() ?? 0,
      modifiedMs: (map['modifiedMs'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 导出时用的文件名（车机的下载目录和手机的浏览器都用它）。
String lyricExportFileName([DateTime? now]) {
  final at = now ?? DateTime.now();
  String two(int value) => value.toString().padLeft(2, '0');
  return 'lyriccar-${at.year}${two(at.month)}${two(at.day)}'
      '-${two(at.hour)}${two(at.minute)}.json';
}

/// 导出 / 导入失败时抛这个，[message] 是能直接显示给用户的中文。
class LyricTransferException implements Exception {
  const LyricTransferException(this.message);

  final String message;

  @override
  String toString() => message;
}

class LyricTransfer {
  LyricTransfer._();

  static const MethodChannel _channel = MethodChannel('carlyric/transfer');

  /// 把一段文本写进系统「下载」目录，返回落盘的路径。
  ///
  /// Android 9 及以下会弹一次存储权限；用户拒绝 → [LyricTransferException]。
  static Future<String> saveToDownloads({
    required String name,
    required String content,
  }) async {
    try {
      final path = await _channel.invokeMethod<String>('saveToDownloads', {
        'name': name,
        'content': content,
      });
      if (path == null || path.isEmpty) {
        throw const LyricTransferException('系统没给出保存路径');
      }
      return path;
    } on PlatformException catch (error) {
      throw LyricTransferException(_message(error));
    } on MissingPluginException {
      throw const LyricTransferException('这台设备不支持直接写「下载」目录');
    }
  }

  /// 下载目录里能当歌词库导入的文件（`.json`），最近改过的排前面。
  static Future<List<DownloadedFile>> listDownloads() async {
    try {
      final raw =
          await _channel.invokeMethod<List<Object?>>('listDownloads') ?? const [];
      return raw.map(DownloadedFile.fromMap).toList();
    } on PlatformException catch (error) {
      throw LyricTransferException(_message(error));
    } on MissingPluginException {
      throw const LyricTransferException('这台设备读不了「下载」目录');
    }
  }

  static Future<String> read(DownloadedFile file) async {
    try {
      final text = await _channel.invokeMethod<String>('readDownload', {
        'path': file.path,
      });
      return text ?? '';
    } on PlatformException catch (error) {
      throw LyricTransferException(_message(error));
    } on MissingPluginException {
      throw const LyricTransferException('这台设备读不了「下载」目录');
    }
  }

  static Future<void> delete(DownloadedFile file) async {
    try {
      await _channel.invokeMethod<bool>('deleteDownload', {'path': file.path});
    } on PlatformException catch (error) {
      throw LyricTransferException(_message(error));
    } on MissingPluginException {
      throw const LyricTransferException('这台设备改不了「下载」目录');
    }
  }

  /// 原生侧的错误码 → 人话。
  static String _message(PlatformException error) {
    switch (error.code) {
      case 'no_permission':
        return '没有存储权限，写不进「下载」目录';
      case 'busy':
        return '权限对话框还开着，稍等一下再试';
      case 'save_failed':
        return '写入「下载」目录失败：${error.message ?? ''}';
      case 'read_failed':
        return '读文件失败：${error.message ?? ''}';
      case 'list_failed':
        return '列举「下载」目录失败：${error.message ?? ''}';
      default:
        return error.message ?? error.code;
    }
  }
}
