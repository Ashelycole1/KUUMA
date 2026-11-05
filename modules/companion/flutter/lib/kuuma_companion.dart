/// Companion screens. Skeleton from Core: replace the placeholder screens with real ones.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/placeholder_screen.dart';

export 'src/placeholder_screen.dart';

class CompanionFlutterModule implements KuumaFlutterModule {
  @override
  String get prefix => 'companion';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, {
      'en': {'companion.tab.title': 'Help'},
      'sw': {'companion.tab.title': 'Msaada'},
    });
    slots.register(
        prefix,
        ScreenEntry(
          id: 'companion.home',
          slot: KuumaSlot.childTab,
          titleKey: 'companion.tab.title',
          icon: Icons.support_outlined,
          order: 10,
          builder: (_) => const PlaceholderScreen(moduleName: 'Companion', screenId: 'companion.home'),
        ));
  }
}
