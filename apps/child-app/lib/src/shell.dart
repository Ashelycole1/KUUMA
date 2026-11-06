import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'debug_console.dart';
import 'native_registry.dart';

/// The child app shell: bottom navigation built from registered module screens and settings.
/// With developer tools on (KUUMA_DEV_TOOLS), also a modules overview, fake switches and the
/// live event console.
class ChildShell extends StatefulWidget {
  const ChildShell({super.key, required this.slots, required this.registry, this.devTools = kuumaDevTools});

  final SlotRegistry slots;
  final NativeRegistry registry;
  final bool devTools;

  @override
  State<ChildShell> createState() => _ChildShellState();
}

class _Tab {
  const _Tab(this.label, this.icon, this.builder);
  final String label;
  final IconData icon;
  final WidgetBuilder builder;
}

class _ChildShellState extends State<ChildShell> {
  final _hide = QuickHideController();
  List<NativeModule> _modules = const [];
  int _index = 0;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final modules = await widget.registry.modules();
    if (mounted) setState(() => _modules = modules);
  }

  /// Tabs: every child.tab screen the Kotlin side declares (paired with its Flutter widget),
  /// then any Flutter-only child.tab screens, then Modules, Settings and Debug.
  List<_Tab> _tabs(BuildContext context) {
    final declared = [
      for (final m in _modules.where((m) => !m.isFake)) ...m.screens.where((s) => s.slot == KuumaSlot.childTab)
    ];
    final ids = <String>{};
    final tabs = <_Tab>[];
    for (final s in declared) {
      ids.add(s.id);
      final entry = widget.slots.byId(s.id);
      tabs.add(_Tab(context.t(s.titleKey), entry?.icon ?? Icons.extension, entry?.builder ?? (_) => _missing(s.id)));
    }
    for (final e in widget.slots.entries(KuumaSlot.childTab).where((e) => !ids.contains(e.id))) {
      tabs.add(_Tab(context.t(e.titleKey), e.icon, e.builder));
    }
    if (widget.devTools) {
      tabs.add(_Tab(context.t('core.nav.modules'), Icons.widgets_outlined, (_) => _ModulesPage(modules: _modules)));
    }
    tabs.add(_Tab(
        context.t('core.nav.settings'),
        Icons.settings_outlined,
        (_) => _SettingsPage(
            slots: widget.slots,
            modules: _modules,
            registry: widget.registry,
            onChanged: _reload,
            devTools: widget.devTools)));
    if (widget.devTools) {
      tabs.add(_Tab(context.t('core.nav.debug'), Icons.terminal, (_) => DebugConsole(registry: widget.registry)));
    }
    return tabs;
  }

  Widget _missing(String id) =>
      EmptyState(title: id, message: 'Declared by the Kotlin module but no Flutter screen is registered.');

  @override
  Widget build(BuildContext context) {
    final tabs = _tabs(context);
    final index = _index.clamp(0, tabs.length - 1);
    return QuickHide(
      controller: _hide,
      lockScreen: (ctx) => LockScreen(
        // PLACEHOLDER: the real PIN check (salted hash in encrypted storage) is Companion's to build.
        verifyPin: (pin) async => pin == '0000',
        biometrics: LocalBiometricAuth(),
        onUnlocked: () {
          _hide.reveal();
          Navigator.of(ctx).pop();
        },
      ),
      child: Scaffold(
        appBar: AppBar(title: Text(tabs[index].label), actions: [QuickHideButton(controller: _hide)]),
        body: tabs[index].builder(context),
        bottomNavigationBar: NavigationBar(
          selectedIndex: index,
          onDestinationSelected: (i) => setState(() => _index = i),
          destinations: [for (final t in tabs) NavigationDestination(icon: Icon(t.icon), label: t.label)],
        ),
      ),
    );
  }
}

class _ModulesPage extends StatelessWidget {
  const _ModulesPage({required this.modules});

  final List<NativeModule> modules;

  @override
  Widget build(BuildContext context) {
    final real = modules.where((m) => !m.isFake).toList();
    if (real.isEmpty) {
      return const EmptyState(title: 'No native modules', message: 'Run on a phone to see the module registry.');
    }
    return ListView(children: [
      for (final m in real)
        KuumaCard(
          leading: const Icon(Icons.check_circle_outline),
          title: m.displayName,
          subtitle: m.screens.isEmpty
              ? 'Background module (no screens)'
              : 'Screens: ${m.screens.map((s) => s.id).join(', ')}',
          child: m.permissions.isEmpty
              ? null
              : Text('Permissions: ${m.permissions.map((p) => p.split('.').last).join(', ')}'),
        ),
    ]);
  }
}

class _SettingsPage extends StatelessWidget {
  const _SettingsPage(
      {required this.slots,
      required this.modules,
      required this.registry,
      required this.onChanged,
      required this.devTools});

  final SlotRegistry slots;
  final List<NativeModule> modules;
  final NativeRegistry registry;
  final VoidCallback onChanged;
  final bool devTools;

  @override
  Widget build(BuildContext context) {
    final fakes = modules.where((m) => m.isFake).toList();
    return ListView(children: [
      for (final e in slots.entries(KuumaSlot.childSettings))
        ListTile(
          leading: Icon(e.icon),
          title: Text(context.t(e.titleKey)),
          onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
            builder: (c) => Scaffold(appBar: AppBar(title: Text(c.t(e.titleKey))), body: e.builder(c)),
          )),
        ),
      if (devTools && fakes.isNotEmpty) ...[
        const Divider(),
        const ListTile(title: Text('Fakes (debug)'), subtitle: Text('Switch each module\'s fake on or off')),
        for (final f in fakes)
          SwitchListTile(
            title: Text(f.displayName),
            value: f.enabled,
            onChanged: (v) async {
              await registry.setFakeEnabled(f.namespace, v);
              onChanged();
            },
          ),
      ],
    ]);
  }
}
