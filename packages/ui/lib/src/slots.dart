import 'package:flutter/widgets.dart';

import 'l10n.dart';

/// Where in the apps a screen can go.
abstract final class KuumaSlot {
  /// A tab in the child app's bottom navigation.
  static const childTab = 'child.tab';

  /// An entry in the child app's settings screen.
  static const childSettings = 'child.settings';

  /// A tab in the parent app's bottom navigation.
  static const parentTab = 'parent.tab';

  static const all = {childTab, childSettings, parentTab};
}

/// One screen a module provides. [id] must match the ScreenSlot id the module's Kotlin
/// side declares (for child-app screens), so the shell can pair them up.
class ScreenEntry {
  const ScreenEntry({
    required this.id,
    required this.slot,
    required this.titleKey,
    required this.icon,
    required this.builder,
    this.order = 100,
  });

  final String id;
  final String slot;
  final String titleKey;
  final IconData icon;
  final WidgetBuilder builder;

  /// Lower comes first.
  final int order;
}

/// Modules register screens here; the shells build their navigation from it.
class SlotRegistry {
  final Map<String, ScreenEntry> _entries = {};

  void register(String owner, ScreenEntry entry) {
    if (!KuumaSlot.all.contains(entry.slot)) throw ArgumentError('unknown slot ${entry.slot}');
    if (!entry.id.startsWith('$owner.') || !entry.titleKey.startsWith('$owner.')) {
      throw ArgumentError('$owner may only register screens and keys under "$owner."');
    }
    if (_entries.containsKey(entry.id)) throw StateError('screen ${entry.id} already registered');
    _entries[entry.id] = entry;
  }

  List<ScreenEntry> entries(String slot) =>
      _entries.values.where((e) => e.slot == slot).toList()..sort((a, b) => a.order.compareTo(b.order));

  ScreenEntry? byId(String id) => _entries[id];
}

/// What each module's Flutter package exports. The shell calls [register] once at startup.
abstract class KuumaFlutterModule {
  /// The owner prefix: companion, casefile or guardian.
  String get prefix;

  void register(SlotRegistry slots, KuumaL10n l10n);
}
