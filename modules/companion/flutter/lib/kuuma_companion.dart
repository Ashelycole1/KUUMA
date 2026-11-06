/// Companion screens: the child's Help tab, advice cards, crisis support and feedback.
/// Everything goes through [CompanionBridge] to Companion's Kotlin half, which publishes the events.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/bridge.dart';
import 'src/debug_screen.dart';
import 'src/home_screen.dart';
import 'src/strings.dart';

export 'src/advice_screen.dart';
export 'src/bridge.dart';
export 'src/crisis_screen.dart';
export 'src/debug_screen.dart';
export 'src/fake_bridge.dart';
export 'src/home_screen.dart';

class CompanionFlutterModule implements KuumaFlutterModule {
  CompanionFlutterModule({CompanionBridge? bridge, bool? debug})
      : bridge = bridge ?? ChannelCompanionBridge(),
        debug = debug ?? kuumaDevTools;

  final CompanionBridge bridge;

  /// Registers the fake screen (publish events on demand) in the settings slot.
  final bool debug;

  @override
  String get prefix => 'companion';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, companionStrings);
    slots.register(
        prefix,
        ScreenEntry(
          id: 'companion.home',
          slot: KuumaSlot.childTab,
          titleKey: 'companion.tab.title',
          icon: Icons.support_outlined,
          order: 10,
          builder: (_) => CompanionHomeScreen(bridge: bridge),
        ));
    if (debug) {
      slots.register(
          prefix,
          ScreenEntry(
            id: 'companion.debug',
            slot: KuumaSlot.childSettings,
            titleKey: 'companion.debug.title',
            icon: Icons.bug_report_outlined,
            order: 900,
            builder: (_) => CompanionDebugScreen(bridge: bridge),
          ));
    }
  }
}
