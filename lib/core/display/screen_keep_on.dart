// ============================================================
// 屏幕常亮开关
//
// Android 侧就是窗口的 FLAG_KEEP_SCREEN_ON（见 MainActivity.setKeepScreenOn）：
// 只让系统别自动灭屏，不是 PowerManager.WakeLock，所以不需要权限、也不会漏电。
//
// 当前唯一的使用方是「歌词界面」（见 shared/widgets/keep_screen_on.dart）：
// 看歌词时不息屏，其它界面照常息屏。
// ============================================================
import 'package:flutter/services.dart';

class ScreenKeepOn {
  ScreenKeepOn._();

  static const MethodChannel _channel = MethodChannel('stools/screen');

  /// 设置常亮。幂等，重复设同一个值也安全（原生侧 addFlags/clearFlags 幂等）。
  static Future<void> set(bool enabled) async {
    try {
      await _channel.invokeMethod<bool>('setKeepScreenOn', {
        'enabled': enabled,
      });
    } catch (_) {
      // 平台不支持（如单元测试、桌面端）时静默忽略：
      // 这只是省电策略，不该因为它让页面报错。
    }
  }
}
