import 'dart:async';

import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'native_registry.dart';

/// Debug-only: every bus event, live. Message text and display names are removed by the
/// Android bridge before events reach Flutter, even here.
class DebugConsole extends StatefulWidget {
  const DebugConsole({super.key, required this.registry});

  final NativeRegistry registry;

  @override
  State<DebugConsole> createState() => _DebugConsoleState();
}

class _DebugConsoleState extends State<DebugConsole> {
  final List<Map<dynamic, dynamic>> _events = [];
  StreamSubscription<Map<dynamic, dynamic>>? _sub;
  List<String> _scenarios = const [];

  @override
  void initState() {
    super.initState();
    _sub = widget.registry.events().listen((e) {
      if (mounted) setState(() => _events.insert(0, e));
    });
    widget.registry.scenarios().then((s) {
      if (mounted) setState(() => _scenarios = s);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      if (_scenarios.isNotEmpty)
        SizedBox(
          height: 56,
          child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.all(8), children: [
            for (final s in _scenarios)
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: ActionChip(label: Text('Play $s'), onPressed: () => widget.registry.playScenario(s)),
              ),
            ActionChip(label: const Text('Clear'), onPressed: () => setState(_events.clear)),
          ]),
        ),
      Expanded(
        child: _events.isEmpty
            ? const EmptyState(
                icon: Icons.terminal,
                title: 'No events yet',
                message: 'Run `make fake SCENARIO=luganda-insults` or tap Play.')
            : ListView.builder(
                itemCount: _events.length,
                itemBuilder: (context, i) => _EventTile(_events[i]),
              ),
      ),
    ]);
  }
}

class _EventTile extends StatelessWidget {
  const _EventTile(this.event);

  final Map<dynamic, dynamic> event;

  @override
  Widget build(BuildContext context) {
    final type = event['type'] as String;
    final spec = Events.byType(type, event['version'] as int);
    final payload = (event['payload'] as Map<dynamic, dynamic>?) ?? const {};
    final severity = payload['severity'] ?? payload['topSeverity'];
    return ListTile(
      dense: true,
      leading: Icon(spec?.phoneOnly == true ? Icons.phone_android : Icons.cloud_outlined, size: 18),
      title: Text(type, style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
      subtitle: Text(
        '${event['occurredAt']}  ${payload.entries.where((e) => e.value is! Map && e.value is! List).map((e) => '${e.key}=${e.value}').take(4).join('  ')}',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 11),
      ),
      trailing: severity is String ? SeverityBadge(Severity.fromWire(severity)) : null,
    );
  }
}
