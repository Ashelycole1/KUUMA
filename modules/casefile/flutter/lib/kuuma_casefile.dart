/// Casefile screens. Skeleton from Core: replace the placeholder screens with real ones.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/placeholder_screen.dart';

export 'src/placeholder_screen.dart';

class CasefileFlutterModule implements KuumaFlutterModule {
  @override
  String get prefix => 'casefile';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, {
      'en': {'casefile.tab.title': 'My cases'},
      'sw': {},
    });
    slots.register(
        prefix,
        ScreenEntry(
          id: 'casefile.cases',
          slot: KuumaSlot.childTab,
          titleKey: 'casefile.tab.title',
          icon: Icons.folder_outlined,
          order: 20,
          builder: (_) => const PlaceholderScreen(moduleName: 'Casefile', screenId: 'casefile.cases'),
        ));
  }
}
