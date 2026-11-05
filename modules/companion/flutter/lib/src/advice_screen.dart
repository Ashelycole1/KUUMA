import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'bridge.dart';

/// Advice cards for one flag: what to do, how to block and report on that app, what not to
/// delete, and a "this isn't abuse" button. Nothing from the message itself is shown.
class AdviceScreen extends StatefulWidget {
  const AdviceScreen({super.key, required this.plan, required this.bridge});

  final AdvicePlanView plan;
  final CompanionBridge bridge;

  @override
  State<AdviceScreen> createState() => _AdviceScreenState();
}

class _AdviceScreenState extends State<AdviceScreen> {
  final Set<String> _reported = {};

  /// Reports a card as shown the first time it is built on screen (Kotlin also removes repeats).
  void _shown(String key) {
    if (_reported.add(key)) widget.bridge.adviceShown(widget.plan.flagId, key);
  }

  @override
  Widget build(BuildContext context) {
    final plan = widget.plan;
    return Scaffold(
      appBar: AppBar(title: Text(context.t('companion.advice.title'))),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          for (final card in plan.cards)
            Builder(builder: (context) {
              _shown(card.key);
              return KuumaCard(
                key: ValueKey(card.key),
                title: card.text,
                child: card.steps.isEmpty ? null : _Steps(card.steps),
              );
            }),
          if (plan.blockSteps.isNotEmpty)
            _StepsTile(title: context.t('companion.advice.block'), steps: plan.blockSteps, icon: Icons.block),
          if (plan.reportSteps.isNotEmpty)
            _StepsTile(title: context.t('companion.advice.report'), steps: plan.reportSteps, icon: Icons.flag_outlined),
          FeedbackBar(flagId: plan.flagId, bridge: widget.bridge),
        ],
      ),
    );
  }
}

class _Steps extends StatelessWidget {
  const _Steps(this.steps);

  final List<String> steps;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final (i, s) in steps.indexed)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                SizedBox(width: 24, child: Text('${i + 1}.')),
                Expanded(child: Text(s)),
              ]),
            ),
        ],
      );
}

class _StepsTile extends StatelessWidget {
  const _StepsTile({required this.title, required this.steps, required this.icon});

  final String title;
  final List<String> steps;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Card(
        child: ExpansionTile(
          leading: Icon(icon),
          title: Text(title),
          childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          children: [_Steps(steps)],
        ),
      );
}

/// "This isn't abuse": publishes companion.feedback.submitted. Training consent is off unless
/// the child turns it on.
class FeedbackBar extends StatefulWidget {
  const FeedbackBar({super.key, required this.flagId, required this.bridge});

  final String flagId;
  final CompanionBridge bridge;

  @override
  State<FeedbackBar> createState() => _FeedbackBarState();
}

class _FeedbackBarState extends State<FeedbackBar> {
  bool _sent = false;

  Future<void> _notAbuse() async {
    final consent = await showDialog<bool>(context: context, builder: (_) => const _ConsentDialog());
    if (consent == null || !mounted) return;
    await widget.bridge.submitFeedback(widget.flagId, FeedbackVerdict.notHarmful, consentToTrain: consent);
    if (mounted) setState(() => _sent = true);
  }

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.all(16),
        child: _sent
            ? Text(context.t('companion.feedback.thanks'), textAlign: TextAlign.center)
            : Column(children: [
                Text(context.t('companion.feedback.question'), style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 8),
                KuumaButton(
                  key: const Key('companion-not-abuse'),
                  kind: KuumaButtonKind.secondary,
                  label: context.t('companion.feedback.not_abuse'),
                  onPressed: _notAbuse,
                ),
              ]),
      );
}

class _ConsentDialog extends StatefulWidget {
  const _ConsentDialog();

  @override
  State<_ConsentDialog> createState() => _ConsentDialogState();
}

class _ConsentDialogState extends State<_ConsentDialog> {
  bool _consent = false;

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(context.t('companion.feedback.not_abuse')),
        content: CheckboxListTile(
          key: const Key('companion-consent'),
          contentPadding: EdgeInsets.zero,
          value: _consent,
          onChanged: (v) => setState(() => _consent = v ?? false),
          title: Text(context.t('companion.feedback.consent')),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(context.t('companion.feedback.cancel'))),
          FilledButton(
            key: const Key('companion-feedback-send'),
            onPressed: () => Navigator.of(context).pop(_consent),
            child: Text(context.t('companion.feedback.send')),
          ),
        ],
      );
}
