import 'package:flutter/material.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  final slots = SlotRegistry();
  GuardianFlutterModule().register(slots, KuumaL10n.instance);
  runApp(KuumaParentApp(slots: slots));
}

class KuumaParentApp extends StatelessWidget {
  const KuumaParentApp({super.key, required this.slots});

  final SlotRegistry slots;

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Kuuma Parent',
        theme: KuumaTheme.light(),
        darkTheme: KuumaTheme.dark(),
        supportedLocales: kuumaLocales,
        localizationsDelegates: kuumaLocalizationsDelegates,
        home: _Root(slots: slots),
      );
}

class _Root extends StatefulWidget {
  const _Root({required this.slots});

  final SlotRegistry slots;

  @override
  State<_Root> createState() => _RootState();
}

class _RootState extends State<_Root> {
  bool _signedIn = false;

  @override
  Widget build(BuildContext context) =>
      _signedIn ? ParentShell(slots: widget.slots) : LoginPlaceholder(onSignIn: () => setState(() => _signedIn = true));
}

/// PLACEHOLDER login until Guardian builds real sign-in. Signs in as the seed test parent.
class LoginPlaceholder extends StatelessWidget {
  const LoginPlaceholder({super.key, required this.onSignIn});

  final VoidCallback onSignIn;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: SafeArea(
          child: EmptyState(
            icon: Icons.family_restroom,
            title: 'Kuuma for parents',
            message: 'Sign-in is a placeholder. You will be signed in as the local test parent.',
            action: KuumaButton(label: 'Continue as test parent', onPressed: onSignIn),
          ),
        ),
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
