/// Guardian screens. Skeleton from Core: replace the placeholder screens with real ones.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/placeholder_screen.dart';

export 'src/placeholder_screen.dart';

class GuardianFlutterModule implements KuumaFlutterModule {
  @override
  String get prefix => 'guardian';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, {
      'en': {
        'guardian.pairing.title': 'Pair with a parent',
        'guardian.parent.alerts.title': 'Alerts',
        'guardian.parent.children.title': 'Children'
      },
      'sw': {},
    });
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.pairing',
          slot: KuumaSlot.childSettings,
          titleKey: 'guardian.pairing.title',
          icon: Icons.link_outlined,
          order: 10,
          builder: (_) => const PlaceholderScreen(moduleName: 'Guardian', screenId: 'guardian.pairing'),
        ));
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.parent.alerts',
          slot: KuumaSlot.parentTab,
          titleKey: 'guardian.parent.alerts.title',
          icon: Icons.notifications_none,
          order: 10,
          builder: (_) => const PlaceholderScreen(moduleName: 'Guardian', screenId: 'guardian.parent.alerts'),
        ));
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.parent.children',
          slot: KuumaSlot.parentTab,
          titleKey: 'guardian.parent.children.title',
          icon: Icons.family_restroom,
          order: 20,
          builder: (_) => const PlaceholderScreen(moduleName: 'Guardian', screenId: 'guardian.parent.children'),
        ));
  }
}
