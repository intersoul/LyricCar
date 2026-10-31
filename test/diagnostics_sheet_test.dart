// ============================================================
// 诊断日志面板的界面测试
//
// 重点只有两条：面板能开（不溢出）、把「怎么把日志给我」写清楚了。
// 远程测试的人是照着这几行操作的，写漏了就等于没这功能。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/core/theme/app_theme.dart';
import 'package:lyriccar/features/carlyric/presentation/widgets/diagnostics_sheet.dart';

Future<void> _pumpSheet(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1280, 720);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  await tester.pumpWidget(
    ProviderScope(
      child: MaterialApp(
        theme: AppTheme.dark,
        home: Scaffold(
          body: Builder(
            builder: (context) => Center(
              child: ElevatedButton(
                onPressed: () => showDiagnosticsSheet(context),
                child: const Text('打开'),
              ),
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
  testWidgets('打开后写清两步：先播一首歌，再导出', (tester) async {
    await _pumpSheet(tester);

    expect(find.text('诊断日志'), findsOneWidget);
    expect(find.textContaining('先随便播一首歌'), findsOneWidget);
    expect(find.textContaining('导出到下载'), findsWidgets);
    expect(tester.takeException(), isNull, reason: '1280×720 上车机上不能溢出');
  });

  testWidgets('没有日志时也不崩，只是提示还没有', (tester) async {
    await _pumpSheet(tester);
    expect(find.text('还没有日志'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('三个出口都在：下载 / 手机来取 / 复制', (tester) async {
    await _pumpSheet(tester);
    expect(find.text('导出到下载'), findsOneWidget);
    expect(find.text('用手机来取'), findsOneWidget);
    expect(find.text('复制全部'), findsOneWidget);
  });
}
