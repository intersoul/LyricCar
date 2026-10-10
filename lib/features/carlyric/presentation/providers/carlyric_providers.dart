// ============================================================
// LyricCar 歌词的状态装配
//
// 与旧代码一样的思路：重活放在纯 Dart 类里（LyricCaptureEngine / LyricStore），
// Provider 只负责把它们接到界面上。
//
// `lyricStoreProvider` 与 `initialSettingsProvider` 在 main() 里 override ——
// 它们要在 runApp 之前就把文件读好（否则第一帧是空的，车机上看着像没启动）。
// ============================================================
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/carlyric_settings.dart';
import '../../data/lyric_store.dart';
import '../../data/media_session_channel.dart';
import '../../domain/lyric_capture_engine.dart';
import '../../domain/media_info.dart';
import '../../domain/stored_song.dart';

/// 歌词库实例（main() 里 override）。
final lyricStoreProvider = Provider<LyricStore>(
  (ref) => throw StateError('lyricStoreProvider 必须在 main() 中 override'),
);

/// 启动时读到的设置（main() 里 override），作为设置 Notifier 的初始值。
final initialSettingsProvider = Provider<CarlyricSettings>(
  (ref) => const CarlyricSettings(),
);

final mediaSessionChannelProvider = Provider<MediaSessionChannel>(
  (ref) => MediaSessionChannel(),
);

/// 系统媒体会话的实时状态。原生侧状态一变就推一次。
final mediaInfoProvider = StreamProvider<MediaInfo>((ref) {
  return ref.watch(mediaSessionChannelProvider).watch();
});

/// 「通知使用权」是否已授予。App 回到前台时要 invalidate 重查
/// （用户可能刚去设置里开完）。
final notificationAccessProvider = FutureProvider<bool>((ref) {
  return ref.watch(mediaSessionChannelProvider).hasNotificationAccess();
});

/// 本应用的通知监听组件名，用来拼那条 adb 命令。
final listenerComponentProvider = FutureProvider<String>((ref) {
  return ref.watch(mediaSessionChannelProvider).listenerComponent();
});

// ------------------------------------------------------------
// 设置
// ------------------------------------------------------------
final carlyricSettingsProvider =
    NotifierProvider<CarlyricSettingsController, CarlyricSettings>(
      CarlyricSettingsController.new,
    );

class CarlyricSettingsController extends Notifier<CarlyricSettings> {
  @override
  CarlyricSettings build() => ref.watch(initialSettingsProvider);

  Future<void> update(CarlyricSettings next) async {
    if (next == state) return;
    state = next;
    await next.save();
  }

  Future<void> setField(LyricFieldPreference field) =>
      update(state.copyWith(field: field));

  Future<void> setKeepScreenOn(bool enabled) =>
      update(state.copyWith(keepScreenOn: enabled));
}

// ------------------------------------------------------------
// 采集 / 回放
// ------------------------------------------------------------
final lyricSessionProvider =
    NotifierProvider<LyricSessionController, LyricSessionState>(
      LyricSessionController.new,
    );

class LyricSessionController extends Notifier<LyricSessionState> {
  LyricCaptureEngine? _engine;

  @override
  LyricSessionState build() {
    final store = ref.watch(lyricStoreProvider);
    final engine = _engine = LyricCaptureEngine(
      sink: store,
      field: ref.read(carlyricSettingsProvider).field,
    );

    // 车机上没法开 DevTools 的时候，这一行行就是唯一的现场证据：
    //   adb logcat -s flutter | grep LyricCar
    var previous = const LyricSessionState();

    void trace(LyricSessionState next) {
      if (next.mode != previous.mode) {
        debugPrint(
          '[LyricCar] 状态 → ${next.mode.name}《${next.media.title}》'
          '${next.lines.length} 句',
        );
      }
      previous = next;
    }

    // 用户在设置里换了「歌词写在歌手行 / 专辑行」→ 立刻按新规则收
    ref.listen(carlyricSettingsProvider, (_, next) => engine.setField(next.field));

    ref.listen(mediaInfoProvider, (_, next) {
      final info = next.value;
      if (info == null) return;
      final state = engine.handle(info);
      trace(state);
      this.state = state;
    });

    // 订阅之前可能已经推过一份（原生侧 onListen 会立刻回推），补一次
    final current = ref.read(mediaInfoProvider).value;
    return current == null ? const LyricSessionState() : engine.handle(current);
  }

  /// 手动重扫一遍（比如用户刚在设置里改完播放器）。
  void rescan() {
    final info = ref.read(mediaInfoProvider).value;
    if (info != null) state = _engine?.handle(info) ?? state;
  }
}

// ------------------------------------------------------------
// 歌词库列表
// ------------------------------------------------------------
final lyricLibraryProvider =
    NotifierProvider<LyricLibraryController, List<StoredSong>>(
      LyricLibraryController.new,
    );

class LyricLibraryController extends Notifier<List<StoredSong>> {
  @override
  List<StoredSong> build() {
    final store = ref.watch(lyricStoreProvider);
    // 库内容一变（采到新的一句 / 引擎删掉随手跳过的歌 / 导入）列表就跟着变
    void sync() => state = store.songs;
    store.revision.addListener(sync);
    ref.onDispose(() => store.revision.removeListener(sync));
    return store.songs;
  }

  void refresh() => state = ref.read(lyricStoreProvider).songs;

  void remove(String key) {
    ref.read(lyricStoreProvider).remove(key);
    refresh();
  }

  void clear() {
    ref.read(lyricStoreProvider).clear();
    refresh();
  }

  /// 导入一段导出 JSON，返回结果供界面提示。
  ImportResult importJson(String text) {
    final result = ref.read(lyricStoreProvider).importJson(text);
    refresh();
    return result;
  }
}
