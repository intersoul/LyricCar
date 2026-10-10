import 'package:flutter/widgets.dart';

import '../../core/display/screen_keep_on.dart';

/// 包住一段 UI，在 [enabled] 为 true 时让屏幕保持常亮。
///
/// 用 `StatefulWidget` 而不是在 `build` 里直接调平台通道 —— 后者每次重建都会
/// 发一次 MethodChannel（滚动歌词时一秒好几次）。这里只在**取值变化**时下发，
/// 并在销毁时确保关掉，避免退出页面后屏幕一直亮着。
///
/// ⚠️ 只应该有一个实例处于 enabled 状态：它是「置位/清位」而非引用计数，
/// 两个实例会互相把对方的标志清掉。
class KeepScreenOn extends StatefulWidget {
  const KeepScreenOn({super.key, required this.enabled, required this.child});

  final bool enabled;
  final Widget child;

  @override
  State<KeepScreenOn> createState() => _KeepScreenOnState();
}

class _KeepScreenOnState extends State<KeepScreenOn> {
  @override
  void initState() {
    super.initState();
    if (widget.enabled) ScreenKeepOn.set(true);
  }

  @override
  void didUpdateWidget(covariant KeepScreenOn oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.enabled == widget.enabled) return;
    ScreenKeepOn.set(widget.enabled);
  }

  @override
  void dispose() {
    // 页面退出时一定要清掉，否则「歌词界面」关掉后屏幕再也不休眠
    if (widget.enabled) ScreenKeepOn.set(false);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
