/// Guardian screens: the parent app (account, children, alerts, approvals) and the
/// child phone's setup wizard.
library;

import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/alerts_screen.dart';
import 'src/api.dart';
import 'src/approvals_screen.dart';
import 'src/children_screen.dart';
import 'src/fake_api.dart';
import 'src/parent_gate.dart';
import 'src/session.dart';
import 'src/setup_wizard.dart';
import 'src/strings.dart';

export 'src/alerts_screen.dart';
export 'src/api.dart';
export 'src/approvals_screen.dart';
export 'src/children_screen.dart';
export 'src/fake_api.dart';
export 'src/family_crypto.dart';
export 'src/parent_gate.dart';
export 'src/session.dart';
export 'src/setup_wizard.dart';
export 'src/strings.dart' show appLabel, guideKey;

/// Run the parent app with no server: `--dart-define=KUUMA_GUARDIAN_FAKE=true`.
const _useFake = bool.fromEnvironment('KUUMA_GUARDIAN_FAKE');

class GuardianFlutterModule implements KuumaFlutterModule {
  GuardianFlutterModule({ParentSession? session, this.biometrics, this.scanner = cameraScanner, ModuleChannel? channel})
      : _session = session,
        _channel = channel;

  ParentSession? _session;
  final BiometricAuth? biometrics;
  final ScannerBuilder scanner;
  final ModuleChannel? _channel;

  /// The parent's session, shared by every parent tab. Built on first use.
  ParentSession get session =>
      _session ??= ParentSession(api: _useFake ? FakeGuardianParentApi() : HttpGuardianParentApi());

  /// The parent app wraps its whole shell in this: account creation, then PIN or fingerprint.
  Widget gate(Widget child) => ParentGate(session: session, biometrics: biometrics, child: child);

  @override
  String get prefix => 'guardian';

  @override
  void register(SlotRegistry slots, KuumaL10n l10n) {
    l10n.register(prefix, guardianStrings);
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.pairing',
          slot: KuumaSlot.childSettings,
          titleKey: 'guardian.pairing.title',
          icon: Icons.link_outlined,
          order: 10,
          builder: (_) => GuardianSetupScreen(channel: _channel ?? ModuleChannel('guardian'), scanner: scanner),
        ));
    Widget scoped(Widget screen) => ParentScope(session: session, child: screen);
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.parent.alerts',
          slot: KuumaSlot.parentTab,
          titleKey: 'guardian.parent.alerts.title',
          icon: Icons.notifications_none,
          order: 10,
          builder: (_) => scoped(const AlertsScreen()),
        ));
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.parent.children',
          slot: KuumaSlot.parentTab,
          titleKey: 'guardian.parent.children.title',
          icon: Icons.family_restroom,
          order: 20,
          builder: (_) => scoped(const ChildrenScreen()),
        ));
    slots.register(
        prefix,
        ScreenEntry(
          id: 'guardian.parent.approvals',
          slot: KuumaSlot.parentTab,
          titleKey: 'guardian.parent.approvals.title',
          icon: Icons.task_alt,
          order: 30,
          builder: (_) => scoped(const ApprovalsScreen()),
        ));
  }
}
