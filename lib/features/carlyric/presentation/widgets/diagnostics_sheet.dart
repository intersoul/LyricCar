// ============================================================
// 诊断日志面板
//
// 目的只有一个：**让没有电脑、也接不了 adb 的人，把现场给我带回来。**
//
// 三条出口，按「对方的条件」挑：
//   * 手机和车机在同一个 Wi-Fi → 局域网（手机浏览器打开，直接下载 txt）；
//   * 车机能用文件管理器 → 导出到「下载」目录；
//   * 只是想快速说一句 → 复制，直接发微信。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/diagnostics.dart';
import '../../data/lyric_transfer.dart';
import 'lan_sheet.dart';

Future<void> showDiagnosticsSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _DiagnosticsSheet(),
  );
}

class _DiagnosticsSheet extends ConsumerStatefulWidget {
  const _DiagnosticsSheet();

  @override
  ConsumerState<_DiagnosticsSheet> createState() => _DiagnosticsSheetState();
}

class _DiagnosticsSheetState extends ConsumerState<_DiagnosticsSheet> {
  String _status = '';
  bool _busy = false;

  Future<void> _export() async {
    final diagnostics = Diagnostics.current;
    if (diagnostics == null) {
      setState(() => _status = '日志还没装上（这不是正常情况）');
      return;
    }
    setState(() {
      _busy = true;
      _status = '';
    });
    try {
      final text = await diagnostics.snapshot();
      final path = await LyricTransfer.saveToDownloads(
        name: diagnosticsFileName(),
        content: text,
      );
      if (!mounted) return;
      setState(() => _status = '已存到「下载」目录：$path');
    } on LyricTransferException catch (error) {
      if (!mounted) return;
      setState(() => _status = '导出失败：${error.message}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _copy() async {
    final diagnostics = Diagnostics.current;
    if (diagnostics == null) return;
    await Clipboard.setData(ClipboardData(text: await diagnostics.snapshot()));
    if (!mounted) return;
    setState(() => _status = '已复制到剪贴板');
  }

  Future<void> _clear() async {
    await Diagnostics.current?.clear();
    if (!mounted) return;
    setState(() => _status = '日志已清空');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final lines = Diagnostics.current?.recent ?? const <String>[];

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 8, 28, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('诊断日志', style: theme.textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(
              '换了车机发现不出歌词时，按这两步做，把日志发给我就能定位：\n'
              '① 先随便播一首歌、放半分钟（让日志里留下播放器发出来的东西）；\n'
              '② 回来点「导出到下载」，把下载目录里的 .txt 发我。\n'
              '日志里只有系统版本、歌名、歌手和播放状态，没有账号密码。',
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.6,
              ),
            ),
            const SizedBox(height: 16),
            if (_status.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Text(
                  _status,
                  style: theme.textTheme.bodyMedium!.copyWith(
                    color: theme.colorScheme.primary,
                  ),
                ),
              ),
            Wrap(
              spacing: 10,
              runSpacing: 10,
              children: [
                FilledButton.icon(
                  onPressed: _busy ? null : _export,
                  icon: const Icon(Icons.download_outlined, size: 18),
                  label: const Text('导出到下载'),
                ),
                OutlinedButton.icon(
                  onPressed: () => showLanSheet(context),
                  icon: const Icon(Icons.wifi_tethering, size: 18),
                  label: const Text('用手机来取'),
                ),
                OutlinedButton.icon(
                  onPressed: _copy,
                  icon: const Icon(Icons.content_copy, size: 18),
                  label: const Text('复制全部'),
                ),
                TextButton.icon(
                  onPressed: _clear,
                  icon: const Icon(Icons.delete_outline, size: 18),
                  label: const Text('清空'),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '最近 ${lines.length} 行（导出的是完整文件）：',
              style: theme.textTheme.bodySmall!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 6),
            Container(
              height: 220,
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerLow,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: theme.colorScheme.onSurfaceVariant.withValues(
                    alpha: 0.2,
                  ),
                ),
              ),
              child: lines.isEmpty
                  ? Text(
                      '还没有日志',
                      style: theme.textTheme.bodySmall!.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    )
                  : SingleChildScrollView(
                      reverse: true,
                      child: SelectableText(
                        lines.reversed.join('\n'),
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 11,
                          height: 1.45,
                          color: Color(0xFFB9C7D2),
                        ),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
