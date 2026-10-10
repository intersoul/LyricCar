// ============================================================
// LyricCar 歌词的设置
//
// 只有两项，都直接决定采集能不能成功：
//   * field        —— 歌词被播放器写进了哪一行（不同播放器不一样，必须可调）
//   * keepScreenOn —— 看歌词时别自动灭屏
//
// 存成 json 文件（不用 shared_preferences，少一个依赖），位置和歌词库同一个目录。
// ============================================================
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import '../domain/lyric_capture_engine.dart';
import 'lyric_store.dart';

const String _fileName = 'settings.json';

class CarlyricSettings {
  const CarlyricSettings({
    this.field = LyricFieldPreference.auto,
    this.keepScreenOn = true,
  });

  final LyricFieldPreference field;
  final bool keepScreenOn;

  CarlyricSettings copyWith({
    LyricFieldPreference? field,
    bool? keepScreenOn,
  }) => CarlyricSettings(
    field: field ?? this.field,
    keepScreenOn: keepScreenOn ?? this.keepScreenOn,
  );

  Map<String, Object?> toJson() => {
    'field': field.name,
    'keepScreenOn': keepScreenOn,
  };

  factory CarlyricSettings.fromJson(Map<String, dynamic> json) =>
      CarlyricSettings(
        field: LyricFieldPreference.fromName(json['field'] as String?),
        keepScreenOn: json['keepScreenOn'] as bool? ?? true,
      );

  static Future<CarlyricSettings> load({Directory? directory}) async {
    try {
      final dir = directory ?? await carlyricDirectory();
      final file = File('${dir.path}/$_fileName');
      if (!file.existsSync()) return const CarlyricSettings();
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! Map) return const CarlyricSettings();
      return CarlyricSettings.fromJson(decoded.cast<String, dynamic>());
    } catch (error) {
      debugPrint('设置读取失败，用默认值：$error');
      return const CarlyricSettings();
    }
  }

  Future<void> save({Directory? directory}) async {
    try {
      final dir = directory ?? await carlyricDirectory();
      if (!dir.existsSync()) dir.createSync(recursive: true);
      await File(
        '${dir.path}/$_fileName',
      ).writeAsString(jsonEncode(toJson()), flush: true);
    } catch (error) {
      debugPrint('设置写入失败：$error');
    }
  }
}
