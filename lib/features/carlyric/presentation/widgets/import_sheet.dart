// ============================================================
// 从「下载」目录导入
//
// 导出是写进系统「下载」目录的，导入就从同一个地方列出来选 —— 换车机时把文件
// 拷进新车机的「下载」目录，这里一选就能续上。不弹系统文件选择器：这台车机
// 上 SAF 那套并不全（见 LyricTransfer 的注释）。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/lyric_transfer.dart';
import '../providers/carlyric_providers.dart';

Future<void> showImportSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _ImportSheet(),
  );
}

class _ImportSheet extends ConsumerStatefulWidget {
  const _ImportSheet();

  @override
  ConsumerState<_ImportSheet> createState() => _ImportSheetState();
}

class _ImportSheetState extends ConsumerState<_ImportSheet> {
  List<DownloadedFile>? _files;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    setState(() {
      _files = null;
      _error = null;
    });
    try {
      final files = await LyricTransfer.listDownloads();
      if (!mounted) return;
      setState(() => _files = files);
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = '$error');
    }
  }

  Future<void> _import(DownloadedFile file) async {
    if (_busy) return;
    setState(() => _busy = true);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    try {
      final text = await LyricTransfer.read(file);
      final result = ref.read(lyricLibraryProvider.notifier).importJson(text);
      ref.read(lyricSessionProvider.notifier).rescan();
      navigator.pop();
      messenger.showSnackBar(
        SnackBar(content: Text('《${file.name}》${result.summary}')),
      );
    } catch (error) {
      if (!mounted) return;
      setState(() => _busy = false);
      messenger.showSnackBar(SnackBar(content: Text('导入失败：$error')));
    }
  }

  Future<void> _delete(DownloadedFile file) async {
    try {
      await LyricTransfer.delete(file);
      await _reload();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('删除失败：$error')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final files = _files;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 8, 28, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('从「下载」导入', style: theme.textTheme.titleLarge),
                const Spacer(),
                TextButton.icon(
                  onPressed: _reload,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: const Text('刷新'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              _error != null
                  ? '读不到「下载」目录：$_error'
                  : '这里有下载目录里所有 .json 文件。换车机时先把导出的文件拷进来。',
              style: theme.textTheme.bodyMedium!.copyWith(
                color: _error != null
                    ? theme.colorScheme.error
                    : theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            if (files == null && _error == null)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 36),
                child: Center(child: CircularProgressIndicator()),
              )
            else if (files == null || files.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 30),
                child: Center(
                  child: Text(
                    '下载目录里还没有 .json 文件。\n先在歌词库面板点「导出」，就会出现在这里。',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyLarge!.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                      height: 1.6,
                    ),
                  ),
                ),
              )
            else
              // 车机屏幕矮，给列表一个上限
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: MediaQuery.sizeOf(context).height * 0.55,
                ),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: files.length,
                  itemBuilder: (context, index) {
                    final file = files[index];
                    return _FileTile(
                      file: file,
                      busy: _busy,
                      onImport: () => _import(file),
                      onDelete: () => _delete(file),
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _FileTile extends StatelessWidget {
  const _FileTile({
    required this.file,
    required this.busy,
    required this.onImport,
    required this.onDelete,
  });

  final DownloadedFile file;
  final bool busy;
  final VoidCallback onImport;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: ListTile(
        contentPadding: EdgeInsets.zero,
        leading: const Icon(Icons.description_outlined),
        title: Text(file.name, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(
          '${_size(file.size)} · ${_time(file.modifiedAt)}',
          style: theme.textTheme.bodySmall,
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            FilledButton(
              onPressed: busy ? null : onImport,
              child: const Text('导入'),
            ),
            IconButton(
              tooltip: '删除这个文件',
              onPressed: onDelete,
              icon: const Icon(Icons.delete_outline, size: 20),
            ),
          ],
        ),
      ),
    );
  }

  static String _size(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }

  static String _time(DateTime at) {
    String two(int value) => value.toString().padLeft(2, '0');
    return '${at.year}-${two(at.month)}-${two(at.day)} '
        '${two(at.hour)}:${two(at.minute)}';
  }
}
