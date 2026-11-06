import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';

/// Kuuma's design tokens. Source of truth: design-system/kuuma/MASTER.md.
///
/// Soft lavender and wellness green, calm and never alarming. Severity colours stay muted so a
/// glance at the screen by someone else does not reveal much, and always come with a text label.
class KuumaPalette {
  const KuumaPalette({
    required this.brand,
    required this.primary,
    required this.onPrimary,
    required this.accent,
    required this.background,
    required this.surface,
    required this.surfaceMuted,
    required this.foreground,
    required this.muted,
    required this.border,
    required this.destructive,
    required this.low,
    required this.medium,
    required this.high,
  });

  final Color brand;
  final Color primary;
  final Color onPrimary;
  final Color accent;
  final Color background;
  final Color surface;
  final Color surfaceMuted;
  final Color foreground;
  final Color muted;
  final Color border;
  final Color destructive;
  final Color low;
  final Color medium;
  final Color high;

  Color severity(Severity s) => switch (s) {
        Severity.low => low,
        Severity.medium => medium,
        Severity.high => high,
      };
}

abstract final class KuumaColors {
  static const light = KuumaPalette(
    brand: Color(0xFF5D40A4),
    primary: Color(0xFF5D40A4),
    onPrimary: Color(0xFFF8FAFD),
    accent: Color(0xFFEDB390),
    background: Color(0xFFF8FAFD),
    surface: Color(0xFFFFFFFF),
    surfaceMuted: Color(0xFFFBF2ED),
    foreground: Color(0xFF2B2140),
    muted: Color(0xFF5B5470),
    border: Color(0xFFE8D9D2),
    destructive: Color(0xFFB91C1C),
    low: Color(0xFF256A51),
    medium: Color(0xFF7F560B),
    high: Color(0xFFA8433A),
  );

  static const dark = KuumaPalette(
    brand: Color(0xFFB9A6E6),
    primary: Color(0xFFB9A6E6),
    onPrimary: Color(0xFF271B43),
    accent: Color(0xFFEDB390),
    background: Color(0xFF1A1722),
    surface: Color(0xFF26212E),
    surfaceMuted: Color(0xFF30283C),
    foreground: Color(0xFFF8FAFD),
    muted: Color(0xFFC0B8CD),
    border: Color(0xFF453A50),
    destructive: Color(0xFFF8A3A3),
    low: Color(0xFF7FCFAE),
    medium: Color(0xFFE3B866),
    high: Color(0xFFF09A8F),
  );

  /// The palette for the current brightness.
  static KuumaPalette of(BuildContext context) => Theme.of(context).brightness == Brightness.dark ? dark : light;

  // Light-mode constants, kept for existing callers. Prefer KuumaColors.of(context).
  static const primary = Color(0xFF5D40A4);
  static const secondary = Color(0xFFEDB390);
  static const surface = Color(0xFFF8FAFD);
  static const low = Color(0xFF256A51);
  static const medium = Color(0xFF7F560B);
  static const high = Color(0xFFA8433A);

  static Color severity(Severity s) => light.severity(s);
}

/// Spacing scale (dp).
abstract final class KuumaSpace {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 12.0;
  static const lg = 16.0;
  static const xl = 24.0;
  static const xxl = 32.0;
  static const xxxl = 48.0;
}

abstract final class KuumaRadius {
  static const sm = 12.0;
  static const md = 16.0;
  static const lg = 24.0;
}

abstract final class KuumaMotion {
  static const fast = Duration(milliseconds: 150);
  static const normal = Duration(milliseconds: 200);

  /// Zero when the phone asks for less motion.
  static Duration of(BuildContext context, [Duration d = normal]) =>
      MediaQuery.maybeDisableAnimationsOf(context) == true ? Duration.zero : d;
}

abstract final class KuumaTheme {
  static const headingFont = 'packages/kuuma_ui/VarelaRound';
  static const bodyFont = 'packages/kuuma_ui/NunitoSans';

  static ThemeData light() => _base(Brightness.light, KuumaColors.light);
  static ThemeData dark() => _base(Brightness.dark, KuumaColors.dark);

  static TextTheme _text(KuumaPalette p) {
    TextStyle h(double size, double height) =>
        TextStyle(fontFamily: headingFont, fontSize: size, height: height / size, color: p.foreground);
    TextStyle b(double size, double height, [FontWeight w = FontWeight.w400, Color? c]) =>
        TextStyle(fontFamily: bodyFont, fontSize: size, height: height / size, fontWeight: w, color: c ?? p.foreground);
    return TextTheme(
      displaySmall: h(32, 40),
      headlineMedium: h(28, 36),
      headlineSmall: h(24, 32),
      titleLarge: h(20, 28),
      titleMedium: b(16, 24, FontWeight.w600),
      titleSmall: b(15, 22, FontWeight.w700),
      bodyLarge: b(16, 24),
      bodyMedium: b(15, 22),
      bodySmall: b(13, 18, FontWeight.w400, p.muted),
      labelLarge: b(15, 20, FontWeight.w700),
      labelMedium: b(13, 18, FontWeight.w600),
      labelSmall: b(12, 16, FontWeight.w600, p.muted),
    );
  }

  static ThemeData _base(Brightness b, KuumaPalette p) {
    final scheme = ColorScheme(
      brightness: b,
      primary: p.primary,
      onPrimary: p.onPrimary,
      primaryContainer: p.surfaceMuted,
      onPrimaryContainer: p.foreground,
      secondary: p.accent,
      onSecondary: b == Brightness.dark ? p.background : Colors.white,
      secondaryContainer: p.surfaceMuted,
      onSecondaryContainer: p.foreground,
      tertiary: p.brand,
      onTertiary: p.onPrimary,
      error: p.destructive,
      onError: b == Brightness.dark ? p.background : Colors.white,
      surface: p.surface,
      onSurface: p.foreground,
      onSurfaceVariant: p.muted,
      surfaceContainerLowest: p.surface,
      surfaceContainerLow: p.background,
      surfaceContainer: p.surfaceMuted,
      surfaceContainerHigh: p.surfaceMuted,
      surfaceContainerHighest: p.surfaceMuted,
      outline: p.muted,
      outlineVariant: p.border,
    );
    final text = _text(p);
    final pill = RoundedRectangleBorder(borderRadius: BorderRadius.circular(999));
    // Large touch targets: many users are on small, low-end phones.
    const minTarget = Size(64, 52);
    return ThemeData(
      useMaterial3: true,
      brightness: b,
      colorScheme: scheme,
      fontFamily: bodyFont,
      textTheme: text,
      scaffoldBackgroundColor: p.background,
      visualDensity: VisualDensity.standard,
      materialTapTargetSize: MaterialTapTargetSize.padded,
      splashFactory: InkSparkle.splashFactory,
      appBarTheme: AppBarTheme(
        backgroundColor: p.background,
        foregroundColor: p.foreground,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: text.titleLarge,
      ),
      cardTheme: CardTheme(
        color: p.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg, vertical: KuumaSpace.sm - 2),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(KuumaRadius.md),
          side: BorderSide(color: p.border),
        ),
        clipBehavior: Clip.antiAlias,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: minTarget,
          shape: pill,
          backgroundColor: p.primary,
          foregroundColor: p.onPrimary,
          textStyle: text.labelLarge,
          padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg, vertical: KuumaSpace.md),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: minTarget,
          shape: pill,
          foregroundColor: p.primary,
          side: BorderSide(color: p.border, width: 1.5),
          textStyle: text.labelLarge,
          padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg, vertical: KuumaSpace.md),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          minimumSize: const Size(48, 48),
          foregroundColor: p.primary,
          textStyle: text.labelLarge,
          shape: pill,
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(minimumSize: const Size(48, 48), foregroundColor: p.foreground),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: p.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg, vertical: KuumaSpace.lg),
        labelStyle: text.bodyMedium?.copyWith(color: p.muted),
        floatingLabelStyle: text.bodyMedium?.copyWith(color: p.primary, fontWeight: FontWeight.w600),
        helperStyle: text.bodySmall,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(KuumaRadius.sm),
          borderSide: BorderSide(color: p.border, width: 1.5),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(KuumaRadius.sm),
          borderSide: BorderSide(color: p.border, width: 1.5),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(KuumaRadius.sm),
          borderSide: BorderSide(color: p.primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(KuumaRadius.sm),
          borderSide: BorderSide(color: p.destructive, width: 1.5),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: p.surface,
        selectedColor: p.surfaceMuted,
        side: BorderSide(color: p.border),
        shape: pill,
        labelStyle: text.labelMedium,
        padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.md, vertical: KuumaSpace.sm),
        checkmarkColor: p.primary,
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: p.surface,
        surfaceTintColor: Colors.transparent,
        indicatorColor: p.surfaceMuted,
        elevation: 0,
        height: 72,
        labelTextStyle: WidgetStateProperty.resolveWith(
          (s) => text.labelMedium?.copyWith(
            color: s.contains(WidgetState.selected) ? p.primary : p.muted,
            fontWeight: s.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w600,
          ),
        ),
        iconTheme: WidgetStateProperty.resolveWith(
          (s) => IconThemeData(color: s.contains(WidgetState.selected) ? p.primary : p.muted),
        ),
      ),
      listTileTheme: ListTileThemeData(
        iconColor: p.muted,
        textColor: p.foreground,
        minVerticalPadding: KuumaSpace.md,
        contentPadding: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(KuumaRadius.sm)),
      ),
      dividerTheme: DividerThemeData(color: p.border, thickness: 1, space: 1),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: p.foreground,
        contentTextStyle: text.bodyMedium?.copyWith(color: p.background),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(KuumaRadius.sm)),
      ),
      dialogTheme: DialogTheme(
        backgroundColor: p.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(KuumaRadius.lg)),
        titleTextStyle: text.titleLarge,
        contentTextStyle: text.bodyMedium,
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: p.surface,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(KuumaRadius.lg))),
      ),
      bannerTheme: MaterialBannerThemeData(backgroundColor: p.surfaceMuted, contentTextStyle: text.bodyMedium),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.onPrimary : p.muted),
        trackColor:
            WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.primary : p.surfaceMuted),
        trackOutlineColor: WidgetStateProperty.all(p.border),
      ),
      radioTheme: RadioThemeData(
          fillColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? p.primary : p.muted)),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: p.brand, linearTrackColor: p.surfaceMuted),
    );
  }
}
