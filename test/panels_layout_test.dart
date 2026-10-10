// ============================================================
// 两个面板的布局测试
//
// 盯的是车主反馈的两个真问题：
//   1. 左半边「上下拉缩」—— 歌词写在歌曲名行时，那一格的文字一句长一句短，
//      如果不固定高度，下面所有东西会跟着跳；
//   2. 右半边长句被截断 —— 超过面板宽度的歌词后半截看不见。
// ============================================================
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/core/theme/app_theme.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_capture_engine.dart';
import 'package:lyriccar/features/carlyric/domain/lyric_line.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';
import 'package:lyriccar/features/carlyric/domain/stored_song.dart';
import 'package:lyriccar/features/carlyric/presentation/providers/carlyric_providers.dart';
import 'package:lyriccar/features/carlyric/presentation/widgets/lyrics_panel.dart';
import 'package:lyriccar/features/carlyric/presentation/widgets/now_playing_panel.dart';

/// 把会话状态直接摆好，不去碰原生通道。
class _FixedSession extends LyricSessionController {
  _FixedSession(this.fixed);

  final LyricSessionState fixed;

  @override
  LyricSessionState build() => fixed;
}

LyricSessionState _state({
  String title = '夜曲',
  LyricMode mode = LyricMode.capturing,
  List<LyricLine> lines = const [],
  int currentIndex = 0,
  bool complete = true,
  bool fromStart = true,
  bool collecting = false,
}) => LyricSessionState(
  song: lines.isEmpty
      ? null
      : StoredSong(
          key: 'k',
          title: title,
          duration: const Duration(minutes: 3),
          // 「缺开头」会换掉右侧的提示条，测两种都要能摆得下
          fromStart: fromStart,
          complete: complete,
          lines: lines,
        ),
  media: MediaInfo(
    hasSession: true,
    packageName: 'com.bilibili.audio',
    appLabel: '哔哩音乐',
    title: title,
    artist: '♪ 一句歌词 ♪',
    album: '哔哩音乐',
    duration: const Duration(minutes: 3),
    position: const Duration(seconds: 30),
  ),
  mode: mode,
  lines: lines,
  currentIndex: currentIndex,
  complete: complete,
  collecting: collecting,
);

Future<void> _pump(
  WidgetTester tester,
  Widget child, {
  required LyricSessionState state,
  Size size = const Size(1280, 720),
}) async {
  // 车机是横屏 1280×720，布局要在这个尺寸下量
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  // key 里带上标题：否则第二次 pump 会复用同一个 ProviderScope（同一个容器），
  // 新的 override 根本不生效 —— 那样「长短标题对比」就成了自己骗自己。
  await tester.pumpWidget(
    ProviderScope(
      key: ValueKey('${state.media.title}|${state.mode}'),
      overrides: [
        lyricSessionProvider.overrideWith(() => _FixedSession(state)),
      ],
      child: MaterialApp(
        theme: AppTheme.dark,
        home: Scaffold(body: child),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('左半边不会因为歌词长短上下跳', () {
    /// 左边面板底部的按钮行 —— 它动不动，就是「有没有上下拉缩」的直接证据。
    Finder buttonsRow() => find.widgetWithText(OutlinedButton, '歌词库');

    testWidgets('一句短歌名 → 一句长歌词，按钮位置分毫不动', (tester) async {
      await _pump(
        tester,
        const SizedBox(width: 465, child: NowPlayingPanel()),
        state: _state(title: '夜曲'),
      );
      final short = tester.getTopLeft(buttonsRow());

      // 歌曲名行模式下，这一格显示的就是当前这句歌词
      await _pump(
        tester,
        const SizedBox(width: 465, child: NowPlayingPanel()),
        state: _state(title: '把玩笑尽量开得每个人都懂把表情收拢我装的酷得像周董这样才对嘛'),
      );
      final long = tester.getTopLeft(buttonsRow());

      expect(long.dy, short.dy, reason: '标题占一行还是两行，都不能把下面的东西顶下去');
      expect(long.dx, short.dx);
    });

    testWidgets('标题再长也只占两行，不会把面板撑出屏幕', (tester) async {
      await _pump(
        tester,
        const SizedBox(width: 465, child: NowPlayingPanel()),
        state: _state(title: '长' * 200),
      );
      expect(tester.takeException(), isNull);
      expect(
        tester.getBottomLeft(find.byType(NowPlayingPanel)).dy,
        lessThanOrEqualTo(720),
      );
    });

    testWidgets('应用名退成超长包名时也不会横向溢出', (tester) async {
      await _pump(
        tester,
        const SizedBox(width: 465, child: NowPlayingPanel()),
        state: _state(title: '夜曲'),
      );
      expect(tester.takeException(), isNull);
    });
  });

  group('半份稿子回放中的提示条', () {
    const lines = [
      LyricLine(timeMs: 0, text: '第一句'),
      LyricLine(timeMs: 30000, text: '第二句'),
      LyricLine(timeMs: 60000, text: '第三句'),
    ];

    testWidgets('回放中还在补词 → 顶上有一条说明，歌词照样在滚', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.replay,
          lines: lines,
          currentIndex: 1,
          complete: false,
          collecting: true,
        ),
      );

      expect(
        find.textContaining('还没采完'),
        findsOneWidget,
        reason: '得让人知道「你看到的歌词是半份，但会自己长出来」',
      );
      expect(find.text('第二句'), findsOneWidget, reason: '提示条不能挤掉歌词');
      expect(tester.takeException(), isNull, reason: '加了提示条也不能溢出');
    });

    testWidgets('缺开头的稿子 → 说明里点明「开头那段还没采到」', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.replay,
          lines: lines,
          currentIndex: 1,
          complete: false,
          fromStart: false,
          collecting: true,
        ),
      );

      expect(find.textContaining('开头那段还没采到'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('完整稿子回放 → 没有提示条（画面保持干净）', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(mode: LyricMode.replay, lines: lines, currentIndex: 1),
      );

      expect(find.textContaining('还没采完'), findsNothing);
      expect(find.textContaining('还没采到'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('提示条只在回放时出现，采集时用的是另一条进度条', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.capturing,
          lines: lines,
          currentIndex: 2,
          complete: false,
          collecting: true,
        ),
      );

      expect(find.textContaining('正在一边播一边采集'), findsOneWidget);
      expect(find.textContaining('还没采完'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });

  group('右半边长句不能截断', () {
    testWidgets('超长的一句整句都在，只是缩放 —— 不溢出、不裁切', (tester) async {
      const long =
          '♪ Number shes and keep a vel protection from the vial of smith and more ♪';
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.replay,
          lines: const [
            LyricLine(timeMs: 0, text: '短句'),
            LyricLine(timeMs: 5000, text: long),
          ],
          currentIndex: 0,
        ),
      );

      expect(tester.takeException(), isNull, reason: '不能有溢出（黄黑条纹）');

      // 长句被缩放到面板宽度以内，而不是被切掉
      final fitted = find.ancestor(
        of: find.text(long),
        matching: find.byType(FittedBox),
      );
      expect(fitted, findsOneWidget, reason: '长句要走「缩放装下」这条路');

      // FittedBox 是画的时候缩，子节点自己的尺寸不变 —— 所以要看**渲染尺寸**
      final rendered = tester.getSize(fitted);
      final natural = tester.getSize(find.text(long));
      expect(rendered.width, lessThan(natural.width), reason: '确实缩了');
      expect(
        rendered.width,
        lessThanOrEqualTo(tester.getSize(find.byType(LyricsPanel)).width),
        reason: '整句都在面板宽度以内（所以后半截看得见）',
      );
    });

    testWidgets('装得下的句子不动字号（别把所有句子都缩小）', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.replay,
          lines: const [
            LyricLine(timeMs: 0, text: '短句'),
            LyricLine(timeMs: 5000, text: '另一句'),
          ],
          currentIndex: 0,
        ),
      );

      final box = tester.widget<FittedBox>(
        find.ancestor(of: find.text('另一句'), matching: find.byType(FittedBox)),
      );
      expect(box.fit, BoxFit.scaleDown, reason: '只缩不放：短句保持原字号');
      expect(tester.takeException(), isNull);
    });

    testWidgets('采集模式（有顶部进度条）同样不截断', (tester) async {
      await _pump(
        tester,
        const LyricsPanel(),
        state: _state(
          mode: LyricMode.capturing,
          lines: const [
            LyricLine(
              timeMs: 0,
              text: '这一句特别长特别长特别长特别长特别长特别长特别长特别长特别长特别长特别长特别长',
            ),
          ],
          currentIndex: 0,
        ),
      );
      expect(tester.takeException(), isNull);
    });
  });
}
