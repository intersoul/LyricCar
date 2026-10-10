// ============================================================
// 设置面板
//
// 只有两项，但「歌词写在」是采集能不能成功的关键开关：
// 蓝牙 AVRCP 只给车机三行（标题 / 歌手 / 专辑），标题必须留给歌名，
// 所以歌词一定挤在「歌手」或「专辑」里 —— 而不同播放器选的行不一样，
// 官方也没有公开约定，只能让用户自己切着试。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/lyric_capture_engine.dart';
import '../providers/carlyric_providers.dart';

Future<void> showSettingsSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (_) => const _SettingsSheet(),
  );
}

class _SettingsSheet extends ConsumerWidget {
  const _SettingsSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final settings = ref.watch(carlyricSettingsProvider);
    final controller = ref.read(carlyricSettingsProvider.notifier);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(28, 8, 28, 28),
        // 说明文字不短，屏幕矮（或字体放大）时会让整个面板顶出屏幕 —— 给滚动兜底
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
            Text('设置', style: theme.textTheme.titleLarge),
            const SizedBox(height: 20),
            Text(
              '歌词写在',
              style: theme.textTheme.titleMedium!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 10),
            for (final field in LyricFieldPreference.values)
              _OptionTile(
                label: field.label,
                selected: settings.field == field,
                onTap: () => controller.setField(field),
              ),
            const SizedBox(height: 12),
            Text(
              '不确定就留「自动判断」：App 会看着标题、歌手、专辑三行，自己认歌词写在\n'
              '哪一行 —— 哔哩音乐这类写在歌手行的，和网易云 / QQ 音乐这类写在歌曲名行\n'
              '的，都能认出来。\n'
              '真认不出来时再手动指定 —— 左侧面板能直接看到这三行的原始内容，\n'
              '歌词写在哪一行一目了然。',
              style: theme.textTheme.bodyMedium!.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 22),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: settings.keepScreenOn,
              onChanged: controller.setKeepScreenOn,
              title: const Text('歌词界面保持常亮'),
              subtitle: const Text('看歌词时不自动熄屏（只是不熄屏，不额外耗电）'),
            ),
          ],
          ),
        ),
      ),
    );
  }
}

class _OptionTile extends StatelessWidget {
  const _OptionTile({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Material(
        color: selected
            ? theme.colorScheme.primary.withValues(alpha: 0.16)
            : theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
            child: Row(
              children: [
                Icon(
                  selected
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked,
                  color: selected
                      ? theme.colorScheme.primary
                      : theme.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 14),
                Text(label, style: theme.textTheme.bodyLarge),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
