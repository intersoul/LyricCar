// ============================================================
// 应用入口
//
// 顺序很讲究：
//   1. 先锁横屏 —— 车机是横屏的，晚一步设置会先闪一帧竖屏；
//   2. 再把歌词库和设置读好 —— 放在 runApp 之前，第一帧就有内容，
//      车机冷启动时不会出现「先空一下再加载」的观感；
//   3. 装诊断日志（接管 debugPrint），跑一次环境自查 —— 别人的车机我看不到，
//      这一份日志就是他发回来给我的唯一证据；
//   4. 最后 runApp，并把手上的这几个实例 override 进 ProviderScope。
//
// 注意这里**没有**初始化任何音频服务：本 App 不接管蓝牙音频，
// 声音由车机自带的蓝牙播放器出，我们只读系统媒体会话。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'features/carlyric/data/carlyric_settings.dart';
import 'features/carlyric/data/diagnostics.dart';
import 'features/carlyric/data/media_session_channel.dart';
import 'features/carlyric/data/lyric_store.dart';
import 'features/carlyric/presentation/providers/carlyric_providers.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  // 内容画到系统栏底下（界面自己用 SafeArea 避让），观感更接近全屏
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      systemNavigationBarColor: Colors.transparent,
    ),
  );

  // 诊断日志要在最前面装：后面所有 debugPrint 才会进文件
  final diagnostics = await Diagnostics.install();

  final store = await LyricStore.open();
  final settings = await CarlyricSettings.load();

  // 环境自查（系统 / 权限 / 各会话的原样元数据）。失败也不能挡启动。
  try {
    await diagnostics.selfCheck(MediaSessionChannel());
  } catch (error) {
    diagnostics.record('环境自查失败：$error');
  }

  runApp(
    ProviderScope(
      overrides: [
        lyricStoreProvider.overrideWithValue(store),
        initialSettingsProvider.overrideWithValue(settings),
      ],
      child: const App(),
    ),
  );
}
