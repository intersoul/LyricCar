import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/theme/app_theme.dart';
import 'features/carlyric/presentation/pages/carlyric_page.dart';
import 'features/carlyric/presentation/providers/carlyric_providers.dart';

/// 应用根组件：装上主题和主页，另外盯着「退到后台」这件事 —— 见下。
///
/// 没有路由表：这个 App 只有一个界面（车机上就该这样，越少层级越好找）。
class App extends ConsumerStatefulWidget {
  const App({super.key});

  @override
  ConsumerState<App> createState() => _AppState();
}

class _AppState extends ConsumerState<App> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 采集时歌词库并不是每一句都写盘（连续变化会合并成每 8 秒一次），
  /// 所以**离开前台前补一刀**：熄火、切到别的 App、被系统回收都不会丢句子。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) return;
    ref.read(lyricStoreProvider).flush();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'LyricCar 歌词',
      debugShowCheckedModeBanner: false,
      // 固定深色：驾驶场景夜里不能晃眼，没必要跟随系统
      theme: AppTheme.dark,
      home: const CarlyricPage(),
    );
  }
}
