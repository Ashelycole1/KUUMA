import 'package:flutter/material.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  final slots = SlotRegistry();
  final guardian = GuardianFlutterModule(biometrics: LocalBiometricAuth());
  guardian.register(slots, KuumaL10n.instance);
  runApp(KuumaParentApp(slots: slots, gate: guardian.gate));
}

class KuumaParentApp extends StatelessWidget {
  const KuumaParentApp({super.key, required this.slots, required this.gate});

  final SlotRegistry slots;

  /// Guardian's account gate (create account, then PIN or fingerprint) around the shell.
  final Widget Function(Widget child) gate;

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Kuuma Parent',
        theme: KuumaTheme.light(),
        darkTheme: KuumaTheme.dark(),
        supportedLocales: kuumaLocales,
        localizationsDelegates: kuumaLocalizationsDelegates,
        // Above the navigator, so locking hides every open screen, not just the tabs.
        builder: (context, child) => gate(child!),
        home: ParentShell(slots: slots),
      );
}

/// Navigation built entirely from the screens Guardian registers in the parent.tab slot.
class ParentShell extends StatefulWidget {
  const ParentShell({super.key, required this.slots});

  final SlotRegistry slots;

  @override
  State<ParentShell> createState() => _ParentShellState();
}

class _ParentShellState extends State<ParentShell> {
  int _index = 0;

  @override
  Widget build(BuildContext context) {
    final entries = widget.slots.entries(KuumaSlot.parentTab);
    if (entries.isEmpty) {
      return const Scaffold(body: EmptyState(title: 'No parent screens registered'));
    }
    final i = _index.clamp(0, entries.length - 1);
    final body = Scaffold(
      appBar: AppBar(title: Text(context.t(entries[i].titleKey))),
      body: entries[i].builder(context),
    );
    if (entries.length < 2) return body; // NavigationBar needs two or more destinations
    return Scaffold(
      body: body,
      bottomNavigationBar: NavigationBar(
        selectedIndex: i,
        onDestinationSelected: (v) => setState(() => _index = v),
        destinations: [
          for (final e in entries) NavigationDestination(icon: Icon(e.icon), label: context.t(e.titleKey))
        ],
      ),
    );
  }
}
