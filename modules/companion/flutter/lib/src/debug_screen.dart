import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'bridge.dart';

/// Companion's fake, for Sentinel and Guardian to test with (debug builds only): publishes
/// companion.capture.requested, companion.sos.triggered and companion.feedback.submitted on demand.
class CompanionDebugScreen extends StatelessWidget {
  const CompanionDebugScreen({super.key, required this.bridge});

  final CompanionBridge bridge;

  static const sampleFlagId = '00000000-0000-4000-8000-000000000001';

  Future<void> _run(BuildContext context, Future<Object?> Function() action) async {
    final messenger = ScaffoldMessenger.of(context);
    final done = context.t('companion.debug.done');
    await action();
    messenger.showSnackBar(SnackBar(content: Text(done)));
  }

  @override
  Widget build(BuildContext context) => ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(context.t('companion.debug.title'), style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          for (final (key, action) in <(String, Future<Object?> Function())>[
            ('companion.debug.capture', bridge.requestCapture),
            ('companion.debug.sos', bridge.triggerSos),
            (
              'companion.debug.feedback',
              () => bridge.submitFeedback(sampleFlagId, FeedbackVerdict.notHarmful, consentToTrain: false)
            ),
          ])
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: KuumaButton(
                kind: KuumaButtonKind.secondary,
                label: context.t(key),
                onPressed: () => _run(context, action),
              ),
            ),
        ],
      );
}
