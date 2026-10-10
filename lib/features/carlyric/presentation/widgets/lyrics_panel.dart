// ============================================================
// 歌词面板（界面右侧的大块区域）
//
// 三种形态，对应采集引擎的三种状态：
//   * idle      系统里没有正在播的媒体 → 教用户怎么用（大概率是权限没开）
//   * capturing 第一次听这首歌 → 边听边攒，最新一句高亮，顶部一条采集进度
//   * replay    这首歌存过了 → 按播放位置同步滚动，点哪句跳到哪句
//
// 自动滚动沿用旧哔哩音乐歌词页的做法：只在「当前句」变化时滚动，滚动位置
// 把当前句放到视口正中，带 320ms 缓动。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/lyric_capture_engine.dart';
import '../../domain/lyric_line.dart';
import '../providers/carlyric_providers.dart';

class LyricsPanel extends ConsumerStatefulWidget {
  const LyricsPanel({super.key});

  @override
  ConsumerState<LyricsPanel> createState() => _LyricsPanelState();
}

class _LyricsPanelState extends ConsumerState<LyricsPanel> {
  static const double _lineHeight = 58;

  final ScrollController _controller = ScrollController();
  int _lastIndex = -1;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _scrollToCurrent(int index) {
    if (!_controller.hasClients || index < 0) return;
    final viewport = _controller.position.viewportDimension;
    final target = (index * _lineHeight - viewport / 2 + _lineHeight / 2).clamp(
      0.0,
      _controller.position.maxScrollExtent,
    );
    _controller.animateTo(
      target,
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    // 只订阅「会改变画面」的那几个字段。
    //
    // 刻意不 watch 整个 state：播放位置每 250ms 变一次，整个 state 跟着变，
    // 会让这块面板一秒重建 4 次（真机实测：空闲时也一直在重绘）。分开 select
    // 之后，只有「当前句变了 / 歌词加了一句 / 状态变了」才重建。
    final mode = ref.watch(lyricSessionProvider.select((s) => s.mode));
    final currentIndex = ref.watch(
      lyricSessionProvider.select((s) => s.currentIndex),
    );
    final lines = ref.watch(lyricSessionProvider.select((s) => s.lines));
    final complete = ref.watch(lyricSessionProvider.select((s) => s.complete));
    final fromStart = ref.watch(
      lyricSessionProvider.select((s) => s.fromStart),
    );
    // 半份稿子回放中：还在一边放一边补
    final collecting = ref.watch(
      lyricSessionProvider.select(
        (s) => s.collecting && s.mode == LyricMode.replay,
      ),
    );

    if (currentIndex != _lastIndex) {
      _lastIndex = currentIndex;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _scrollToCurrent(currentIndex);
      });
    }

    return switch (mode) {
      LyricMode.idle => const _IdleHint(),
      LyricMode.capturing => Column(
        children: [
          _CaptureBanner(
            count: lines.length,
            complete: complete,
            fromStart: fromStart,
          ),
          Expanded(
            child: lines.isNotEmpty
                ? _LyricList(
                    lines: lines,
                    current: currentIndex,
                    controller: _controller,
                  )
                : const _WaitingFirstLine(),
          ),
        ],
      ),
      LyricMode.replay => Column(
        children: [
          if (collecting)
            _FillingBanner(count: lines.length, fromStart: fromStart),
          Expanded(
            child: _LyricList(
              lines: lines,
              current: currentIndex,
              controller: _controller,
              onTap: (line) =>
                  ref.read(mediaSessionChannelProvider).seek(line.time),
            ),
          ),
        ],
      ),
    };
  }
}

// ------------------------------------------------------------
// 歌词列表
// ------------------------------------------------------------
class _LyricList extends StatelessWidget {
  const _LyricList({
    required this.lines,
    required this.current,
    required this.controller,
    this.onTap,
  });

  final List<LyricLine> lines;
  final int current;
  final ScrollController controller;
  final void Function(LyricLine line)? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListView.builder(
      controller: controller,
      padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 28),
      itemCount: lines.length,
      itemExtent: _LyricsPanelState._lineHeight,
      itemBuilder: (context, index) {
        final line = lines[index];
        final active = index == current;
        return GestureDetector(
          onTap: onTap == null ? null : () => onTap!(line),
          behavior: HitTestBehavior.opaque,
          child: Align(
            alignment: Alignment.centerLeft,
            child: AnimatedDefaultTextStyle(
              duration: const Duration(milliseconds: 200),
              style: theme.textTheme.titleLarge!.copyWith(
                fontSize: active ? 30 : 24,
                height: 1.25,
                fontWeight: active ? FontWeight.w700 : FontWeight.w400,
                color: active
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant.withValues(
                        alpha: 0.65,
                      ),
              ),
              // 长句不再被切掉：以前是 maxLines: 1 + softWrap: false，超过面板宽度
              // 的句子直接看不见后半截（车主反馈）。FittedBox 只缩不放 —— 装得下
              // 的句子字号分毫不动，装不下的整句等比缩小到宽度以内，一行的高度
              // 不变，滚动位置的计算也就不用改。
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text(line.text, maxLines: 1, softWrap: false),
              ),
            ),
          ),
        );
      },
    );
  }
}

// ------------------------------------------------------------
// 采集中的两种提示
// ------------------------------------------------------------
class _CaptureBanner extends StatelessWidget {
  const _CaptureBanner({
    required this.count,
    required this.complete,
    required this.fromStart,
  });

  final int count;
  final bool complete;

  /// false = 这次是从中间开始听的，开头那几句没采到。
  final bool fromStart;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    // 三种情况要分清楚，不然后半段听着没问题、用户根本不知道丢了开头：
    //   缺开头 > 没放完 > 已收工
    final (icon, text) = !fromStart
        ? (
            Icons.content_cut,
            '开头没采到（打开 App 时这首歌已经唱了一半）·已 $count 句\n'
                '把这句歌从头再放一遍就会自动补齐，不会重复。',
          )
        : complete
        ? (Icons.check_circle_outline, '这首歌的歌词已经采集完整，下次播放直接显示')
        : (Icons.record_voice_over_outlined, '第一遍听：正在一边播一边采集歌词（已 $count 句）');

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(24, 20, 24, 0),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(icon, size: 20, color: theme.colorScheme.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.primary,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 回放一份「还没采完」的稿子时，顶上挂的一条轻提示。
///
/// 这一条是这次改动的可见部分：以前半份稿子不进回放，界面钉在最后一句上不动
/// （车主反馈「只会一直显示最后一句」）。现在半份稿子照样按时间戳滚，缺的部分
/// 一边放一边补，所以得告诉用户「你看到的歌词是半份，但会自己长出来」。
class _FillingBanner extends StatelessWidget {
  const _FillingBanner({required this.count, required this.fromStart});

  final int count;

  /// false = 这份稿子缺开头（打开 App 时这首歌已经唱了一半）。
  final bool fromStart;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final text = !fromStart
        ? '开头那段还没采到 · 已 $count 句\n'
              '缺的部分会边放边补；从头再放一遍就能把开头补上，已有的句子不会被改乱。'
        : '这首歌还没采完（已 $count 句）· 回放中，缺的部分会自动补上';

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(24, 20, 24, 0),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(
            Icons.playlist_add_check_circle_outlined,
            size: 20,
            color: theme.colorScheme.primary,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.primary,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _WaitingFirstLine extends StatelessWidget {
  const _WaitingFirstLine();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Text(
          '正在等待播放器送出第一句歌词…\n\n'
          '一直不动就去「设置 → 歌词写在」换一行试试。三种情况：\n'
          '· 歌手行 / 专辑行 —— 大部分播放器；\n'
          '· 歌曲名行 —— 网易云 / QQ 音乐的「车载歌词」把歌词写进了歌曲名那一行，\n'
          '  这种必须选「歌曲名行」（默认的「自动判断」认不出来）。',
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyLarge!.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
            height: 1.6,
          ),
        ),
      ),
    );
  }
}

// ------------------------------------------------------------
// 通知使用权拿不到时的兜底提示
// ------------------------------------------------------------
/// 有的车机 ROM（实测 BYD 就是）把「通知使用权」设置页整个删掉了 ——
/// 点「去开启通知使用权」只会弹「多媒体系统不支持该操作」。
/// 那种情况下唯一的办法是在电脑上跑一条 adb，所以这里把确切命令摆出来。
class _AdbHint extends ConsumerWidget {
  const _AdbHint();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final component = ref.watch(listenerComponentProvider).value ?? '';

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '如果点按钮弹的是「不支持该操作」，说明这台车机的设置里没有这个页面。\n'
            '用电脑连上车机跑一次下面的命令即可（只需一次，之后一直有效）：',
            style: theme.textTheme.bodyMedium!.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 12),
          SelectableText(
            component.isEmpty
                ? 'adb shell cmd notification allow_listener 包名/服务类名'
                : 'adb shell cmd notification allow_listener $component',
            style: theme.textTheme.bodyMedium!.copyWith(
              fontFamily: 'monospace',
              color: theme.colorScheme.primary,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}

// ------------------------------------------------------------
// 没有媒体会话时的引导
// ------------------------------------------------------------
class _IdleHint extends ConsumerWidget {
  const _IdleHint();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final access = ref.watch(notificationAccessProvider);
    final granted = access.value ?? false;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(36),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.music_note_outlined, color: theme.colorScheme.primary),
              const SizedBox(width: 10),
              Text(
                granted ? '等待音乐播放' : '先开一个系统权限',
                style: theme.textTheme.titleLarge,
              ),
            ],
          ),
          const SizedBox(height: 18),
          if (!granted) ...[
            Text(
              '读取不了「正在播放哪首歌」的话，歌词就无从采起。\n'
              '请授予「通知使用权」—— 这是 Android 允许普通 App 读取别的\n'
              '播放器（网易云 / QQ 音乐 / 车机自带蓝牙播放器）媒体信息的唯一入口。',
              style: theme.textTheme.bodyLarge!.copyWith(
                height: 1.6,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 22),
            FilledButton.icon(
              onPressed: () => ref
                  .read(mediaSessionChannelProvider)
                  .openNotificationAccessSettings(),
              icon: const Icon(Icons.open_in_new),
              label: const Text('去开启通知使用权'),
            ),
            const SizedBox(height: 14),
            Text(
              '路径：设置 → 应用 → 特殊应用权限 → 通知使用权 → 勾选「LyricCar 歌词」',
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 18),
            const _AdbHint(),
          ] else ...[
            Text(
              '权限已就绪。现在用手机 / 车机上的播放器放一首歌，并确认：\n\n'
              '1. 声音是「车机自带蓝牙播放器」在放（本 App 不去接管音频）；\n'
              '2. 播放器已经把歌词那一行写进了媒体信息（网易云 / QQ 音乐在\n'
              '   设置里叫「车载歌词 / 蓝牙歌词」，要先打开）；\n'
              '3. 第一遍听会把歌词一句句攒下来，第二遍开始就直接显示完整歌词。',
              style: theme.textTheme.bodyLarge!.copyWith(
                height: 1.6,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 22),
            OutlinedButton.icon(
              onPressed: () => ref.invalidate(notificationAccessProvider),
              icon: const Icon(Icons.refresh),
              label: const Text('重新检测'),
            ),
          ],
        ],
      ),
    );
  }
}
