// ============================================================
// 局域网传输面板
//
// 打开面板 = 在车上起一个 HTTP 服务；面板一关就停。手机连同一个局域网，
// 浏览器打开上面显示的地址，就能：
//   * 把车上的歌词库下载到手机；
//   * 把手机里的一份歌词库传到这台车。
//
// 只在开着面板的时候监听端口 —— 传完关掉，不长期暴露在局域网里。
// ============================================================
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/lyric_lan_server.dart';
import '../providers/carlyric_providers.dart';

Future<void> showLanSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _LanSheet(),
  );
}

class _LanSheet extends ConsumerStatefulWidget {
  const _LanSheet();

  @override
  ConsumerState<_LanSheet> createState() => _LanSheetState();
}

class _LanSheetState extends ConsumerState<_LanSheet> {
  LyricLanServer? _server;
  String? _error;
  List<String> _urls = const [];
  Timer? _ticker;
  int _handled = 0;

  @override
  void initState() {
    super.initState();
    _start();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    // 面板一关就收摊，不留监听端口
    _server?.stop();
    super.dispose();
  }

  Future<void> _start() async {
    final server = LyricLanServer(store: ref.read(lyricStoreProvider));
    try {
      await server.start();
    } catch (error) {
      await server.stop();
      if (!mounted) return;
      setState(() => _error = '$error');
      return;
    }
    if (!mounted) {
      await server.stop();
      return;
    }
    setState(() {
      _server = server;
      _urls = server.urls;
      _error = null;
    });
    // 手机上打开了页面 / 传完了，面板上要能看出来
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || _server == null) return;
      setState(() => _handled = _server!.requestCount);
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final primary = _urls.isEmpty ? null : _urls.first;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 8, 28, 24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('局域网传输', style: theme.textTheme.titleLarge),
              const SizedBox(height: 8),
              Text(
                '让手机连上和车机**同一个** Wi-Fi / 热点，然后在手机浏览器里打开下面这个地址。\n'
                '页面里可以下载车上的歌词库，也可以把手机里的歌词库传上来。',
                style: theme.textTheme.bodyMedium!.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.6,
                ),
              ),
              const SizedBox(height: 16),
              if (_error != null)
                _Banner(
                  icon: Icons.error_outline,
                  color: theme.colorScheme.error,
                  text: '起服务失败：$_error',
                )
              else if (primary == null)
                const _Banner(
                  icon: Icons.wifi_off_outlined,
                  color: Colors.orange,
                  text: '没找到局域网地址：车机可能没连 Wi-Fi / 热点。连上后再打开这个面板。',
                )
              else ...[
                FilledButton.icon(
                  onPressed: () => Clipboard.setData(ClipboardData(text: primary)),
                  icon: const Icon(Icons.content_copy, size: 18),
                  label: const Text('复制地址'),
                ),
                const SizedBox(height: 12),
                for (final url in _urls)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: SelectableText(
                      url,
                      style: theme.textTheme.headlineSmall!.copyWith(
                        color: theme.colorScheme.primary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                const SizedBox(height: 8),
                Text(
                  _handled == 0
                      ? '还没收到请求。手机浏览器里打开上面这个地址就行。'
                      : '已经处理 $_handled 个请求 —— 手机连上来了。',
                  style: theme.textTheme.bodyMedium!.copyWith(
                    color: _handled == 0
                        ? theme.colorScheme.onSurfaceVariant
                        : theme.colorScheme.primary,
                  ),
                ),
              ],
              const SizedBox(height: 18),
              Text(
                '注意：\n'
                '· 传输期间别退出这个 App（页面就是它在提供）；\n'
                '· 关掉这个面板就会停掉服务，不会一直开着端口；\n'
                '· 地址里的 IP 每个网络都可能不一样，换网络后重新打开面板看一眼。',
                style: theme.textTheme.bodySmall!.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.7,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.icon, required this.color, required this.text});

  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: color),
          const SizedBox(width: 10),
          Expanded(
            child: Text(text, style: TextStyle(color: color, height: 1.5)),
          ),
        ],
      ),
    );
  }
}
