// ============================================================
// 歌词库面板：看存了哪些歌 + 导出 / 导入 / 删除
//
// 「导出 / 导入」是这套方案能跨车机的原因：歌词不是从网上求来的，是这台车
// 一首首听出来的。把整个库打包成一个 json 带走，新车机导入后就有全部歌词。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/lyric_transfer.dart';
import '../../domain/lyric_line.dart';
import '../../domain/stored_song.dart';
import '../providers/carlyric_providers.dart';
import 'import_sheet.dart';
import 'lan_sheet.dart';

Future<void> showLibrarySheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _LibrarySheet(),
  );
}

class _LibrarySheet extends ConsumerWidget {
  const _LibrarySheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final songs = ref.watch(lyricLibraryProvider);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 8, 28, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('歌词库', style: theme.textTheme.titleLarge),
                const SizedBox(width: 12),
                Text(
                  '${songs.length} 首',
                  style: theme.textTheme.bodyMedium!.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const Spacer(),
                TextButton.icon(
                  onPressed: songs.isEmpty
                      ? null
                      : () => _export(context, ref),
                  icon: const Icon(Icons.ios_share, size: 18),
                  label: const Text('导出到下载'),
                ),
                TextButton.icon(
                  onPressed: () => showImportSheet(context),
                  icon: const Icon(Icons.download_outlined, size: 18),
                  label: const Text('从下载导入'),
                ),
                TextButton.icon(
                  onPressed: () => showLanSheet(context),
                  icon: const Icon(Icons.wifi_tethering, size: 18),
                  label: const Text('局域网传输'),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '歌词是这台车听出来的。「导出到下载」会存进系统「下载」目录；'
              '手机和车机连同一个 Wi-Fi 时，用「局域网传输」直接传最省事。',
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            // 车机屏幕矮，给列表一个上限，别把按钮顶出屏幕
            ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(context).height * 0.5,
              ),
              child: songs.isEmpty
                  ? Padding(
                      padding: const EdgeInsets.symmetric(vertical: 36),
                      child: Center(
                        child: Text(
                          '还没有采集到歌词。\n放一首歌，让它唱完一遍试试。',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodyLarge!.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                            height: 1.6,
                          ),
                        ),
                      ),
                    )
                  : ListView.builder(
                      shrinkWrap: true,
                      itemCount: songs.length,
                      itemBuilder: (context, index) => _SongTile(
                        song: songs[index],
                        onDelete: () {
                          ref
                              .read(lyricLibraryProvider.notifier)
                              .remove(songs[index].key);
                          ref.read(lyricSessionProvider.notifier).rescan();
                        },
                      ),
                    ),
            ),
            if (songs.isNotEmpty) ...[
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  onPressed: () => _confirmClear(context, ref),
                  icon: const Icon(Icons.delete_sweep_outlined, size: 18),
                  label: const Text('清空歌词库'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// 存进系统「下载」目录：车上没有文件管理器的「另存为」可用，
  /// 直接落到下载目录，文件管理器里一眼就能看到，也好拷到别的车机。
  Future<void> _export(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    final store = ref.read(lyricStoreProvider);
    try {
      final path = await LyricTransfer.saveToDownloads(
        name: lyricExportFileName(),
        content: store.exportJson(),
      );
      messenger.showSnackBar(
        SnackBar(
          content: Text('已导出到「下载」：$path'),
          duration: const Duration(seconds: 6),
        ),
      );
    } catch (error) {
      messenger.showSnackBar(SnackBar(content: Text('导出失败：$error')));
    }
  }

  Future<void> _confirmClear(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空歌词库？'),
        content: const Text('所有已采集的歌词都会被删除，且无法恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    ref.read(lyricLibraryProvider.notifier).clear();
    ref.read(lyricSessionProvider.notifier).rescan();
  }
}

class _SongTile extends ConsumerWidget {
  const _SongTile({required this.song, required this.onDelete});

  final StoredSong song;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final subtitle = <String>[
      if (song.artist.isNotEmpty) song.artist,
      '${song.lines.length} 句',
      if (song.ready)
        '完整'
      else if (!song.fromStart)
        '缺开头'
      else
        '未采完',
      song.source.label,
    ].join(' · ');

    return ListTile(
      contentPadding: EdgeInsets.zero,
      minVerticalPadding: 10,
      leading: Icon(
        song.ready ? Icons.check_circle_outline : Icons.incomplete_circle,
        color: song.ready
            ? theme.colorScheme.primary
            : theme.colorScheme.onSurfaceVariant,
      ),
      title: Text(song.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis),
      onTap: () => _preview(context),
      trailing: IconButton(
        onPressed: onDelete,
        icon: const Icon(Icons.delete_outline),
        tooltip: '删除这首歌的歌词',
      ),
    );
  }

  /// 看一眼采集质量：每句前面带上「第几秒」。
  void _preview(BuildContext context) {
    showDialog<void>(
      context: context,
      builder: (context) {
        final theme = Theme.of(context);
        return AlertDialog(
          title: Text(song.title),
          content: SizedBox(
            width: 520,
            height: 420,
            child: ListView.builder(
              itemCount: song.lines.length,
              itemBuilder: (context, index) {
                final LyricLine line = song.lines[index];
                final seconds = (line.timeMs / 1000).toStringAsFixed(1);
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Text(
                    '$seconds s    ${line.text}',
                    style: theme.textTheme.bodyMedium,
                  ),
                );
              },
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('关闭'),
            ),
          ],
        );
      },
    );
  }
}
