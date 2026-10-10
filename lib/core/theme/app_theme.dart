import 'package:flutter/material.dart';

/// LyricCar 歌词界面的主题。
///
/// 驾驶场景的两条硬要求：**深色**（夜里不能晃眼）和**高对比**（瞥一眼能看清）。
/// 所以不用浅色主题，也不跟随系统 —— 直接固定成深色。
class AppTheme {
  AppTheme._();

  static const Color background = Color(0xFF0A0E13);
  static const Color surface = Color(0xFF141B23);
  static const Color accent = Color(0xFF57C7FF);
  static const Color textPrimary = Color(0xFFECEFF1);
  static const Color textSecondary = Color(0xFF8FA3B0);

  static ThemeData get dark {
    final scheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: Brightness.dark,
    ).copyWith(
      surface: background,
      primary: accent,
      onSurface: textPrimary,
      onSurfaceVariant: textSecondary,
      surfaceContainerLow: surface,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      // 车机上显示的字号整体放大一档：手机上的 14 在车机上要凑近才看得清
      textTheme: const TextTheme(
        headlineMedium: TextStyle(fontSize: 34, fontWeight: FontWeight.w700),
        titleLarge: TextStyle(fontSize: 24, fontWeight: FontWeight.w600),
        titleMedium: TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
        bodyLarge: TextStyle(fontSize: 18),
        bodyMedium: TextStyle(fontSize: 15),
        labelLarge: TextStyle(fontSize: 15),
      ),
      iconTheme: const IconThemeData(color: textPrimary),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
          textStyle: const TextStyle(fontSize: 16),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
          textStyle: const TextStyle(fontSize: 15),
        ),
      ),
      dialogTheme: const DialogThemeData(backgroundColor: surface),
      bottomSheetTheme: const BottomSheetThemeData(backgroundColor: surface),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: surface,
        contentTextStyle: TextStyle(fontSize: 16, color: textPrimary),
      ),
    );
  }
}
