import 'package:flutter/material.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'bridge.dart';

/// Calm support for self-harm signals: a few kind words, a breathing exercise and helplines
/// such as Sauti 116. Opened at once when such a flag arrives, and from the Help tab any time.
class CrisisScreen extends StatelessWidget {
  const CrisisScreen({super.key, required this.bridge});

  final CompanionBridge bridge;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      backgroundColor: theme.colorScheme.surfaceContainerLow,
      body: SafeArea(
        child: FutureBuilder<CrisisContent>(
          future: bridge.crisis(),
          builder: (context, snap) {
            final c = snap.data;
            if (c == null) return const Center(child: CircularProgressIndicator());
            return ListView(
              padding: const EdgeInsets.all(24),
              children: [
                const SizedBox(height: 24),
                Icon(Icons.favorite, size: 48, color: theme.colorScheme.primary),
                const SizedBox(height: 16),
                Text(c.title, style: theme.textTheme.headlineMedium, textAlign: TextAlign.center),
                const SizedBox(height: 12),
                Text(c.body, style: theme.textTheme.bodyLarge, textAlign: TextAlign.center),
                const SizedBox(height: 24),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Row(children: [
                      const Icon(Icons.air),
                      const SizedBox(width: 12),
                      Expanded(child: Text(c.breathe, style: theme.textTheme.bodyLarge)),
                    ]),
                  ),
                ),
                const SizedBox(height: 24),
                Text(c.callPrompt, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
                const SizedBox(height: 12),
                for (final h in c.helplines)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: KuumaButton(
                      key: Key('companion-call-${h.id}'),
                      icon: Icons.call,
                      label: context.t('companion.crisis.call', {'name': h.name, 'number': h.number}) +
                          (h.free ? ' · ${context.t('companion.crisis.free')}' : ''),
                      onPressed: () => bridge.dial(h.number),
                    ),
                  ),
                const SizedBox(height: 8),
                TextButton(
                  onPressed: () => Navigator.of(context).maybePop(),
                  child: Text(context.t('companion.crisis.close')),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
