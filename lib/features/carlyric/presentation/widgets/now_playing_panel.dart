// ============================================================
// 「正在播放」面板（界面左侧）
//
// 只做展示与遥控：歌名 / 歌手 / 专辑 / 来源 App 都是从系统媒体会话读来的，
// 播放控制也是转手发给那个播放器（MediaController.transportControls），
// 本 App 自己不出声。
//
// 两个细节：
//   * 歌手行、专辑行会**原样**显示。这两个字段可能正被播放器拿来做歌词，
//     原样显示才看得出「歌词到底写在哪一行」，配「歌词写在」设置一起排查。
//   * 进度条与其它部分是分开订阅的 —— 位置每 250ms 变一次，没必要让标题封面跟着重建。
// ============================================================
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/lyric_capture_engine.dart';
import '../providers/carlyric_providers.dart';
import 'library_sheet.dart';
import 'settings_sheet.dart';

class NowPlayingPanel extends ConsumerWidget {
  const NowPlayingPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final title = ref.watch(lyricSessionProvider.select((s) => s.media.title));
    final artist = ref.watch(
      lyricSessionProvider.select((s) => s.media.artist),
    );
    final album = ref.watch(lyricSessionProvider.select((s) => s.media.album));
    final appLabel = ref.watch(
      lyricSessionProvider.select((s) => s.media.appLabel),
    );
    final hasTrack = ref.watch(
      lyricSessionProvider.select((s) => s.media.hasTrack),
    );
    final art = ref.watch(lyricSessionProvider.select((s) => s.media.artBytes));
    final playing = ref.watch(
      lyricSessionProvider.select((s) => s.media.playing),
    );
    final mode = ref.watch(lyricSessionProvider.select((s) => s.mode));
    final storedCount = ref.watch(
      lyricSessionProvider.select((s) => s.storedCount),
    );
    // 回放中是否还在补词（半份稿子）
    final collecting = ref.watch(
      lyricSessionProvider.select(
        (s) => s.collecting && s.mode == LyricMode.replay,
      ),
    );

    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF16202B), Color(0xFF0A0E13)],
        ),
      ),
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _Cover(art: art),
                const SizedBox(width: 20),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _TitleBlock(title: title),
                      const SizedBox(height: 10),
                      _MetaLine(label: '歌手', value: artist),
                      const SizedBox(height: 4),
                      _MetaLine(label: '专辑', value: album),
                      const SizedBox(height: 12),
                      if (appLabel.isNotEmpty) _SourceChip(label: appLabel),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 26),
            _ProgressRow(hasTrack: hasTrack),
            const SizedBox(height: 18),
            _Controls(hasTrack: hasTrack, playing: playing),
            const SizedBox(height: 24),
            _StatusLine(
              mode: mode,
              storedCount: storedCount,
              hasTrack: hasTrack,
              collecting: collecting,
            ),
            const SizedBox(height: 18),
            Row(
              children: [
                OutlinedButton.icon(
                  onPressed: () => showLibrarySheet(context),
                  icon: const Icon(Icons.library_music_outlined, size: 20),
                  label: const Text('歌词库'),
                ),
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: () => showSettingsSheet(context),
                  icon: const Icon(Icons.settings_outlined, size: 20),
                  label: const Text('设置'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// 标题块 —— 高度**固定两行**，不管里面是一行还是两行字。
///
/// 为什么必须固定：网易云 / QQ 音乐把歌词写在**歌曲名行**，屏幕上这一格显示的就是
/// 「当前这句歌词」，一句长一句短。高度跟着文字走的话，下面所有东西（进度、控制、
/// 状态、按钮）会随歌词的长短上下跳 —— 车主反馈的「左半边上下拉缩」就是这个。
class _TitleBlock extends StatelessWidget {
  const _TitleBlock({required this.title});

  final String title;

  /// 行高倍数。**必须显式写死**：这样「两行有多高」是可算的，不用去猜字体指标。
  static const double _lineHeightFactor = 1.25;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.titleLarge!
        .copyWith(height: _lineHeightFactor);
    final twoLines = style.fontSize! * _lineHeightFactor * 2;

    return SizedBox(
      height: twoLines,
      child: Align(
        alignment: Alignment.topLeft,
        child: Text(
          title.isEmpty ? '未在播放' : title,
          style: style,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}

class _Cover extends StatelessWidget {
  const _Cover({required this.art});

  final Uint8List? art;

  static const double _size = 132;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final bytes = art;
    return ClipRRect(
      borderRadius: BorderRadius.circular(16),
      child: SizedBox(
        width: _size,
        height: _size,
        child: bytes == null
            ? ColoredBox(
                color: theme.colorScheme.surfaceContainerLow,
                child: Icon(
                  Icons.album_outlined,
                  size: 56,
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              )
            : Image.memory(bytes, fit: BoxFit.cover, gaplessPlayback: true),
      ),
    );
  }
}

/// 把「歌手」「专辑」两行原始值显示出来，方便判断歌词被写在哪一行。
class _MetaLine extends StatelessWidget {
  const _MetaLine({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(
      '$label：${value.isEmpty ? '—' : value}',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: theme.textTheme.bodyMedium!.copyWith(
        color: theme.colorScheme.onSurfaceVariant,
      ),
    );
  }
}

class _SourceChip extends StatelessWidget {
  const _SourceChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: theme.colorScheme.primary.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.bluetooth_audio,
            size: 15,
            color: theme.colorScheme.primary,
          ),
          const SizedBox(width: 6),
          // 应用名拿不到时会退成包名（com.kugou.android.auto 这种），会长到撑破面板
          Flexible(
            child: Text(
              '来自 $label',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.primary,
                fontSize: 13,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 进度条 + 时间。只订阅播放位置，避免高频重建整块面板。
class _ProgressRow extends ConsumerWidget {
  const _ProgressRow({required this.hasTrack});

  final bool hasTrack;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final position = ref.watch(
      lyricSessionProvider.select((s) => s.media.position),
    );
    final duration = ref.watch(
      lyricSessionProvider.select((s) => s.media.duration),
    );
    final theme = Theme.of(context);

    final total = duration.inMilliseconds;
    final value = total <= 0
        ? 0.0
        : (position.inMilliseconds / total).clamp(0.0, 1.0);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SliderTheme(
          data: SliderTheme.of(context).copyWith(
            trackHeight: 4,
            thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
          ),
          child: Slider(
            value: value,
            onChanged: !hasTrack || total <= 0
                ? null
                : (next) => ref
                      .read(mediaSessionChannelProvider)
                      .seek(Duration(milliseconds: (next * total).round())),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(_fmt(position), style: theme.textTheme.bodyMedium),
              Text(
                total <= 0 ? '--:--' : _fmt(duration),
                style: theme.textTheme.bodyMedium,
              ),
            ],
          ),
        ),
      ],
    );
  }

  static String _fmt(Duration d) {
    final minutes = d.inMinutes;
    final seconds = d.inSeconds % 60;
    return '$minutes:${seconds.toString().padLeft(2, '0')}';
  }
}

class _Controls extends ConsumerWidget {
  const _Controls({required this.hasTrack, required this.playing});

  final bool hasTrack;
  final bool playing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final channel = ref.read(mediaSessionChannelProvider);

    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        IconButton(
          iconSize: 42,
          onPressed: hasTrack ? channel.previous : null,
          icon: const Icon(Icons.skip_previous),
          tooltip: '上一首',
        ),
        const SizedBox(width: 16),
        IconButton.filled(
          iconSize: 44,
          padding: const EdgeInsets.all(14),
          onPressed: hasTrack ? (playing ? channel.pause : channel.play) : null,
          icon: Icon(playing ? Icons.pause : Icons.play_arrow),
          tooltip: playing ? '暂停' : '播放',
        ),
        const SizedBox(width: 16),
        IconButton(
          iconSize: 42,
          onPressed: hasTrack ? channel.next : null,
          icon: const Icon(Icons.skip_next),
          tooltip: '下一首',
        ),
      ],
    );
  }
}

class _StatusLine extends StatelessWidget {
  const _StatusLine({
    required this.mode,
    required this.storedCount,
    required this.hasTrack,
    required this.collecting,
  });

  final LyricMode mode;
  final int storedCount;
  final bool hasTrack;

  /// 回放中还在补词（这份稿子还没收工）。
  final bool collecting;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final text = switch (mode) {
      LyricMode.idle => '歌词库：$storedCount 首',
      LyricMode.capturing => '采集歌词中 · 歌词库：$storedCount 首',
      LyricMode.replay =>
        collecting
            ? '回放中（还在补没采到的部分） · 歌词库：$storedCount 首'
            : '歌词回放中 · 歌词库：$storedCount 首',
    };
    return Row(
      children: [
        Icon(
          hasTrack ? Icons.graphic_eq : Icons.hourglass_empty,
          size: 18,
          color: theme.colorScheme.onSurfaceVariant,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodyMedium!.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    );
  }
}
