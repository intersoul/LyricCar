// ============================================================
// 诊断日志
//
// 为什么要有它：这台 App 的核心玩法（读蓝牙媒体会话）在不同车机上表现不一样，
// 而别人的车机我没有。以前排查全靠 `adb logcat`，可远程测试的人未必有电脑、
// 也未必会接 adb。所以把日志落到 App 自己的目录里，再给两个「一键拿出来」的出口：
//   * 导出到系统「下载」目录（拷给别人 / 用文件管理器发出去）；
//   * 局域网：手机上打开车机给的地址，直接下载这个日志。
//
// 实现上做了一件偷懒但很有效的事：**接管 `debugPrint`**。整个项目里已有的
// `[LyricCar] ...` 日志一行都不用改，自动进文件 + 进内存环形缓冲（界面预览用）。
//
// 写盘策略跟歌词库一个思路：攒 1 秒再整段追加，不每行一次 IO；文件超过
// [maxBytes] 就滚成 `.1`（只留一份旧的），不会无限长。
// ============================================================
import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'lyric_store.dart' show carlyricDirectory;
import 'media_session_channel.dart';

/// App 版本，写进诊断日志的抬头。
///
/// 没引 `package_info_plus`：为了一个字符串多一个依赖不值当。改版本号时
/// **记得同步这里**（pubspec.yaml 的 version 那一行）。
const String kAppVersion = '1.0.1+2';

class Diagnostics {
  Diagnostics._(this._file, this.maxBytes);

  /// 文件名（导出的文件也叫这个名字）。
  static const String fileName = 'diagnostics.log';

  /// 超过这个大小就滚成 `.1` 重新开始，避免越写越大。
  static const int defaultMaxBytes = 256 * 1024;

  /// 实际上限（测试里会传小值，量的还是同一套滚动逻辑）。
  final int maxBytes;

  /// 内存里保留多少行（界面预览 + 文件读不出来时的兜底）。
  static const int memoryLines = 600;

  /// 攒多久再写一次盘。
  static const Duration flushDelay = Duration(seconds: 1);

  final File _file;
  final List<String> _lines = [];
  final StringBuffer _pending = StringBuffer();
  Timer? _flushTimer;
  Future<void>? _writing;
  DebugPrintCallback? _previousPrinter;
  static Diagnostics? _current;

  /// 已安装的实例（没装过就是 null，界面据此提示）。
  static Diagnostics? get current => _current;

  /// 日志文件（导出、局域网发送都读它）。
  File get file => _file;

  static Future<Diagnostics> install({
    Directory? directory,
    int maxBytes = defaultMaxBytes,
  }) async {
    final dir = directory ?? await carlyricDirectory();
    if (!dir.existsSync()) dir.createSync(recursive: true);
    final diagnostics = Diagnostics._(File('${dir.path}/$fileName'), maxBytes);
    _current = diagnostics;
    diagnostics._hookPrinter();
    diagnostics.section('App 启动');
    diagnostics.record('日志文件：${diagnostics._file.path}');
    return diagnostics;
  }

  /// 接管 `debugPrint`：控制台照旧输出，同时进文件。
  void _hookPrinter() {
    _previousPrinter ??= debugPrint;
    debugPrint = (String? message, {int? wrapWidth}) {
      _previousPrinter?.call(message, wrapWidth: wrapWidth);
      // echo: false —— 上面已经打到控制台了，别再打一遍
      if (message != null && message.isNotEmpty) record(message, echo: false);
    };
  }

  /// 还原（测试用，免得测试之间互相污染）。
  void unhook() {
    final previous = _previousPrinter;
    if (previous != null) debugPrint = previous;
    _previousPrinter = null;
    if (identical(_current, this)) _current = null;
  }

  /// 记一条。多行会被拆开，每行自己带时间戳。
  ///
  /// [echo] 为真时**同时打到控制台**（logcat 里也能看到）—— 自查报告走这条路，
  /// 这样「日志文件里有什么」和「logcat 里有什么」不会对不上。
  void record(String message, {bool echo = true}) {
    final stamp = _stamp();
    for (final raw in message.split('\n')) {
      final line = raw.trimRight();
      if (line.isEmpty) continue;
      final text = '$stamp $line';
      _lines.add(text);
      if (_lines.length > memoryLines) _lines.removeAt(0);
      _pending.writeln(text);
      if (echo) _previousPrinter?.call(text);
    }
    _scheduleFlush();
  }

  /// 一条显眼的分隔线（自查报告用它分块）。
  void section(String title) {
    record('──── $title ────');
  }

  /// 记一项自查结果：`✓ 通知使用权：已授予` / `✗ 媒体会话：0 个`。
  void check(String label, bool ok, [String? detail]) {
    final mark = ok ? '✓' : '✗';
    record(detail == null ? '$mark $label' : '$mark $label：$detail');
  }

  /// 最近这些行（界面预览用）。
  List<String> get recent => List.unmodifiable(_lines);

  /// 环境自查：系统、权限、以及原生侧能读到的每个会话。
  ///
  /// 启动时跑一次，App 回到前台时再跑一次 —— 用户很可能刚去系统设置里把
  /// 「通知使用权」打开，那一趟回来就能看到「可读会话」从 0 变成 N。
  Future<void> selfCheck(MediaSessionChannel channel) async {
    section('环境自查');
    record('App 版本 $kAppVersion');
    record(
      '运行时 ${Platform.operatingSystem} ${Platform.operatingSystemVersion}',
    );
    final hasAccess = await channel.hasNotificationAccess();
    check('通知使用权', hasAccess, hasAccess ? '已授予' : '未授予 —— 读不到别的 App 的媒体会话');
    final component = await channel.listenerComponent();
    record('监听组件：${component.isEmpty ? '（拿不到）' : component}');
    final dump = (await channel.diagnose()).trim();
    section('系统与会话');
    if (dump.isEmpty) {
      record('（读不到会话信息，多半是通知使用权还没开）');
    } else {
      record(dump);
    }
  }

  /// 整份日志：先把攒着的写下去，再读文件；读不到就退回内存里的那几百行。
  ///
  /// 导出 / 局域网都走这个，保证「刚发生的事」也在里面。
  Future<String> snapshot() async {
    await flush();
    try {
      if (_file.existsSync()) {
        final text = await _file.readAsString();
        if (text.trim().isNotEmpty) return text;
      }
    } catch (_) {
      // 读不出来就用内存里的
    }
    return _lines.join('\n');
  }

  /// 立刻把攒着的写进文件。
  Future<void> flush() {
    _flushTimer?.cancel();
    _flushTimer = null;
    final next = (_writing ?? Future<void>.value()).then((_) => _writeNow());
    _writing = next;
    return next;
  }

  Future<void> clear() async {
    _lines.clear();
    _pending.clear();
    _flushTimer?.cancel();
    _flushTimer = null;
    await (_writing ?? Future<void>.value());
    try {
      if (_file.existsSync()) await _file.delete();
    } catch (_) {
      // 删不掉就算了，下次写会覆盖
    }
    record('日志已清空');
  }

  void _scheduleFlush() {
    if (_flushTimer != null) return;
    _flushTimer = Timer(flushDelay, () {
      _flushTimer = null;
      flush();
    });
  }

  Future<void> _writeNow() async {
    if (_pending.isEmpty) return;
    var text = _pending.toString();
    _pending.clear();
    // 一次攒了太多（比如刚启动就刷了一屏）就只留尾巴 —— 保证写进去之后不超标
    if (text.length > maxBytes) {
      text = text.substring(text.length - maxBytes);
    }
    try {
      final dir = _file.parent;
      if (!dir.existsSync()) dir.createSync(recursive: true);
      // 加上这次就超了 → 把旧的一整份滚成 `.1`（只留一份），之后从头写
      final existing = _file.existsSync() ? _file.lengthSync() : 0;
      if (existing > 0 && existing + text.length > maxBytes) {
        final backup = File('${_file.path}.1');
        if (backup.existsSync()) backup.deleteSync();
        _file.renameSync(backup.path);
      }
      await _file.writeAsString(text, mode: FileMode.append, flush: true);
    } catch (_) {
      // 写不进去（目录没了 / 没权限）不能让 App 挂掉，也不能再打日志 —— 会递归
    }
  }

  static String _stamp() {
    final now = DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    return '[${two(now.hour)}:${two(now.minute)}:${two(now.second)}.'
        '${now.millisecond.toString().padLeft(3, '0')}]';
  }
}
