// ============================================================
// 播放位置「单调化」的测试
//
// 真机上抓到的原始序列（就是车主说的「抽搐一下」的元凶）：
//   39269ms → 38516ms → 39483ms
// 换句那一瞬间先退 753ms，于是「当前第几句」跟着退一句再前进。
// ============================================================
import 'package:flutter_test/flutter_test.dart';
import 'package:lyriccar/features/carlyric/data/media_session_channel.dart';
import 'package:lyriccar/features/carlyric/domain/media_info.dart';

MediaInfo _info(int ms, {bool playing = true, bool hasTrack = true}) =>
    MediaInfo(
      hasSession: hasTrack,
      title: hasTrack ? '夜曲' : '',
      position: Duration(milliseconds: ms),
      playing: playing,
      duration: const Duration(minutes: 3),
    );

void main() {
  group('播放位置单调化', () {
    test('真机上那三份推送：回退被压平，不再倒退', () {
      final clock = MonotonicPlaybackClock();

      expect(clock.smooth(_info(39269)).position.inMilliseconds, 39269);
      // 这一份是「协议栈重推」带回来的落后值 —— 要压平
      expect(clock.smooth(_info(38516)).position.inMilliseconds, 39269);
      // 真正的前进照旧
      expect(clock.smooth(_info(39483)).position.inMilliseconds, 39483);
    });

    test('正常前进不受影响', () {
      final clock = MonotonicPlaybackClock();
      expect(clock.smooth(_info(1000)).position.inMilliseconds, 1000);
      expect(clock.smooth(_info(1250)).position.inMilliseconds, 1250);
      expect(clock.smooth(_info(1500)).position.inMilliseconds, 1500);
    });

    test('真拖进度条（回退超过 2 秒）要认', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(60000));
      expect(clock.smooth(_info(20000)).position.inMilliseconds, 20000);
    });

    test('重新放一遍（回到开头）要认', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(180000));
      expect(clock.smooth(_info(300)).position.inMilliseconds, 300);
    });

    test('换歌：从 180 秒切到新歌 0.5 秒 —— 大回退，照认', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(180000));
      expect(
        clock.smooth(_info(500, hasTrack: true)).position.inMilliseconds,
        500,
      );
    });

    test('没有会话时状态清掉，下次从新开始', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(50000));
      clock.smooth(_info(0, hasTrack: false));
      // 新的一首歌从 200ms 开始，不该被上一首的 50 秒压住
      expect(clock.smooth(_info(200)).position.inMilliseconds, 200);
    });

    test('掉回开头不算抖动（上一首 1.8 秒被切走 → 新歌从头放）', () {
      final clock = MonotonicPlaybackClock();
      // 上一首还剩 1.8 秒时被切走，新歌的位置先报了个僵尸值
      clock.smooth(_info(1800));
      // 真的从头开始了 —— 这一份必须原样报出去，不能被压成 1800ms
      expect(clock.smooth(_info(200)).position.inMilliseconds, 200);
    });

    test('凡是「掉回开头」的那一份，都原样报出去（下游靠它判重新放）', () {
      // 僵尸值在 1~2 秒之间时，「真实位置 0.2 秒」的回退幅度不到 2 秒 ——
      // 要不是专门给「掉回开头」留了例外，它就会被当成抖动压掉，
      // 下游永远看不到「回到开头」这个信号。
      for (final stale in [1200, 1800, 2500, 4000, 5500, 10500]) {
        final clock = MonotonicPlaybackClock();
        clock.smooth(_info(stale));
        expect(
          clock.smooth(_info(200)).position.inMilliseconds,
          200,
          reason: '僵尸值 $stale ms',
        );
      }
    });

    test('反过来：几百毫秒的抖动不会被误当成「回到开头」', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(30000));
      final clamped = clock.smooth(_info(29400));
      expect(clamped.position.inMilliseconds, 30000);
    });

    test('暂停时位置不动，也不会被压成奇怪的数', () {
      final clock = MonotonicPlaybackClock();
      clock.smooth(_info(30000));
      final paused = clock.smooth(_info(29900, playing: false));
      expect(paused.position.inMilliseconds, 30000);
      expect(paused.playing, isFalse);
    });
  });
}
