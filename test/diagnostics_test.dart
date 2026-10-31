// ============================================================
// 诊断日志的测试
//
// 这套东西的用处是「别人的车机出问题时，把他的现场带回来」，所以三件事必须对：
//   1. 已有的 `[LyricCar]` 日志（走 debugPrint）能自动进文件 —— 否则白搭；
//   2. 攒着写、但导出前一定先落盘 —— 否则「刚发生的事」会缺；
//   3. 文件不会无限长（超过上限滚一份）。
// ============================================================
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/data/diagnostics.dart';

void main() {
  late Directory dir;
  late Diagnostics diagnostics;

  setUp(() async {
    dir = Directory.systemTemp.createTempSync('lyriccar_diag');
    diagnostics = await Diagnostics.install(directory: dir);
  });

  tearDown(() {
    diagnostics.unhook();
    if (dir.existsSync()) dir.deleteSync(recursive: true);
  });

  test('debugPrint 里的日志会自动进文件（项目里已有的日志一行都不用改）', () async {
    debugPrint('[LyricCar] 换歌：key=夜曲|180 位置=0.0s');
    await diagnostics.flush();

    final text = await diagnostics.snapshot();
    expect(text, contains('换歌：key=夜曲|180'));
    expect(text, contains('App 启动'), reason: '开头要有一次启动记录');
  });

  test('每行自带时间戳', () async {
    diagnostics.record('第一句');
    await diagnostics.flush();
    final text = await diagnostics.snapshot();
    expect(
      RegExp(r'\[\d{2}:\d{2}:\d{2}\.\d{3}\] 第一句').hasMatch(text),
      isTrue,
      reason: '没有时间戳就没法跟日志里的事件对上',
    );
  });

  test('多行一次记进去会拆成多行，各自带时间戳', () async {
    diagnostics.record('第一行\n第二行');
    await diagnostics.flush();
    final text = await diagnostics.snapshot();
    expect(text, contains('第一行'));
    expect(text, contains('第二行'));
    expect(
      RegExp(r'\] 第二行').hasMatch(text),
      isTrue,
      reason: '换行也要有独立时间戳，否则导出后看不清时刻',
    );
  });

  test('snapshot 会先落盘：刚记的东西立刻能导出', () async {
    diagnostics.record('刚刚发生的');
    // 故意不调 flush，也不等那个 1 秒定时器
    final text = await diagnostics.snapshot();
    expect(text, contains('刚刚发生的'));
  });

  test('自查结果用 ✓ / ✗ 标记，一眼能看出哪项没过', () async {
    diagnostics.check('通知使用权', true, '已授予');
    diagnostics.check('可读会话', false, '0 个');
    await diagnostics.flush();
    final text = await diagnostics.snapshot();
    expect(text, contains('✓ 通知使用权：已授予'));
    expect(text, contains('✗ 可读会话：0 个'));
  });

  test('文件超过上限会滚成 .1，不会无限长', () async {
    // 换一个把上限压小的实例，量的是同一套滚动逻辑
    final small = await Diagnostics.install(directory: dir, maxBytes: 1500);
    addTearDown(small.unhook);

    final big = 'x' * 300;
    Future<void> writeBatch(int from) async {
      for (var i = from; i < from + 6; i++) {
        small.record('$i $big');
      }
      await small.flush();
    }

    // 第一批：文件本来不存在，直接写；写得比上限还多就只留尾巴
    await writeBatch(0);
    expect(
      small.file.lengthSync() <= 1500,
      isTrue,
      reason: '当前文件不该超过上限：${small.file.lengthSync()}',
    );

    // 第二批：加上这次就超了 → 旧的滚成 .1
    await writeBatch(6);
    final backup = File('${small.file.path}.1');
    expect(small.file.lengthSync() <= 1500, isTrue, reason: '滚动之后当前文件也不该超过上限');
    expect(backup.existsSync(), isTrue, reason: '超了就该把旧的滚到 .1');
    expect(
      Directory(dir.path).listSync().length,
      lessThanOrEqualTo(3),
      reason: '只留一份旧的，不能越滚越多',
    );
  });

  test('清空之后文件没了，但会记一笔「已清空」', () async {
    diagnostics.record('要被清掉的内容');
    await diagnostics.clear();
    final text = await diagnostics.snapshot();
    expect(text, isNot(contains('要被清掉的内容')));
    expect(text, contains('日志已清空'));
  });

  test('内存里只留最近几百行（界面预览不至于卡）', () {
    for (var i = 0; i < Diagnostics.memoryLines + 50; i++) {
      diagnostics.record('第 $i 行');
    }
    expect(diagnostics.recent.length, Diagnostics.memoryLines);
    expect(
      diagnostics.recent.last,
      contains('第 ${Diagnostics.memoryLines + 49} 行'),
    );
    expect(diagnostics.recent.first, isNot(contains('第 0 行')));
  });

  test('安装后能从 current 拿到实例（界面上要用）', () {
    expect(Diagnostics.current, same(diagnostics));
    diagnostics.unhook();
    expect(Diagnostics.current, isNull);
  });
}
