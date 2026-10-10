// ============================================================
// LyricCar 歌词主界面（横屏全屏）
//
// 左边「正在播放」+ 播放控制，右边整块给歌词。横屏下这样排最合理：
// 车机屏幕又宽又矮，竖着堆会把歌词挤成两三行。
//
// 音频不归这个 App 管 —— 声音由车机自带的蓝牙播放器出，这里只是
// 读系统媒体会话 + 显示歌词 + 顺手转发几个播放控制。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/widgets/keep_screen_on.dart';
import '../providers/carlyric_providers.dart';
import '../widgets/lyrics_panel.dart';
import '../widgets/now_playing_panel.dart';

class CarlyricPage extends ConsumerStatefulWidget {
  const CarlyricPage({super.key});

  @override
  ConsumerState<CarlyricPage> createState() => _CarlyricPageState();
}

class _CarlyricPageState extends ConsumerState<CarlyricPage>
    with WidgetsBindingObserver {
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

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 用户多半是跳去系统设置里开「通知使用权」了，回来立刻重查一次
    if (state == AppLifecycleState.resumed) {
      ref.invalidate(notificationAccessProvider);
    }
  }

  @override
  Widget build(BuildContext context) {
    final keepScreenOn = ref.watch(
      carlyricSettingsProvider.select((s) => s.keepScreenOn),
    );

    return Scaffold(
      body: KeepScreenOn(
        enabled: keepScreenOn,
        child: SafeArea(
          child: LayoutBuilder(
            builder: (context, constraints) {
              // 车机是横屏，正常都走上面那条；竖屏只是兜底，别把内容压扁
              if (constraints.maxWidth < 640) {
                return const Column(
                  children: [
                    Expanded(flex: 5, child: NowPlayingPanel()),
                    Expanded(flex: 6, child: LyricsPanel()),
                  ],
                );
              }
              return const Row(
                children: [
                  Expanded(flex: 4, child: NowPlayingPanel()),
                  VerticalDivider(width: 1),
                  Expanded(flex: 6, child: LyricsPanel()),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
