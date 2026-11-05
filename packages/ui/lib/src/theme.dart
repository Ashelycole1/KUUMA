import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';

/// Kuuma's calm, non-alarming palette. Severity colours are muted so a glance at the
/// screen by someone else does not reveal much.
abstract final class KuumaColors {
  static const primary = Color(0xFF2F6F62);
  static const secondary = Color(0xFF5B7DB1);
  static const surface = Color(0xFFF7F6F2);
  static const low = Color(0xFF6E9F7A);
  static const medium = Color(0xFFC79A3B);
  static const high = Color(0xFFB4584B);

  static Color severity(Severity s) => switch (s) {
        Severity.low => low,
        Severity.medium => medium,
        Severity.high => high,
      };
}

abstract final class KuumaTheme {
  static ThemeData light() => _base(Brightness.light);
  static ThemeData dark() => _base(Brightness.dark);

  static ThemeData _base(Brightness b) {
    final scheme =
        ColorScheme.fromSeed(seedColor: KuumaColors.primary, secondary: KuumaColors.secondary, brightness: b);
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      visualDensity: VisualDensity.standard,
      cardTheme: const CardTheme(margin: EdgeInsets.symmetric(horizontal: 16, vertical: 8)),
      // Large touch targets: many users are on small, low-end phones.
      filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: const Size(64, 48))),
      outlinedButtonTheme: OutlinedButtonThemeData(style: OutlinedButton.styleFrom(minimumSize: const Size(64, 48))),
    );
  }
}
