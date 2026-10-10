// ============================================================
// 设置面板的界面测试
//
// 「歌词写在」是采集能不能成功的总开关（四个选项对应四种播放器做法），
// 面板上少列一个、或者点了不生效，用户就没法救 —— 所以这几条守住它。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/data/carlyric_settings.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/presentation/providers/carlyric_providers.dart';
import 'package:lyriccar/features/carlyric/presentation/widgets/settings_sheet.dart';

/// 不落盘的设置控制器：真机上是写文件，测试里只需要内存里生效。
class _MemorySettings extends CarlyricSettingsController {
  @override
  Future<void> update(CarlyricSettings next) async {
    if (next == state) return;
    state = next;
  }
}

ProviderContainer _container() {
  final container = ProviderContainer(
    overrides: [
      initialSettingsProvider.overrideWithValue(const CarlyricSettings()),
      carlyricSettingsProvider.overrideWith(_MemorySettings.new),
    ],
  );
  addTearDown(container.dispose);
  return container;
}

Future<void> _openSheet(WidgetTester tester, ProviderContainer container) async {
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showSettingsSheet(context),
              child: const Text('打开'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('打开'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('四个「歌词写在」选项都要列出来', (tester) async {
    await _openSheet(tester, _container());

    expect(find.text('自动判断'), findsOneWidget);
    expect(find.text('歌手行'), findsOneWidget);
    expect(find.text('专辑行'), findsOneWidget);
    expect(find.text('歌曲名行'), findsOneWidget, reason: '网易云/QQ 的歌词就在这一行');
  });

  testWidgets('选「歌曲名行」立刻生效', (tester) async {
    final container = _container();
    await _openSheet(tester, container);

    // 默认是自动判断
    expect(container.read(carlyricSettingsProvider).field, LyricFieldPreference.auto);
    expect(
      find.byIcon(Icons.radio_button_checked),
      findsOneWidget,
      reason: '四项里只该有一个被选中',
    );

    await tester.tap(find.text('歌曲名行'));
    await tester.pumpAndSettle();

    expect(container.read(carlyricSettingsProvider).field, LyricFieldPreference.title);
    // 选中标记跟着挪过去（现在落在「歌曲名行」那一项上）
    expect(
      find.descendant(
        of: find.widgetWithText(InkWell, '歌曲名行'),
        matching: find.byIcon(Icons.radio_button_checked),
      ),
      findsOneWidget,
    );
  });
}
