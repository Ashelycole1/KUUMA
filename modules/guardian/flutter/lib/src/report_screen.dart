import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'children_screen.dart';
import 'parent_gate.dart';

IconData recipientIcon(RecipientType r) => switch (r) {
      RecipientType.school => Icons.school_outlined,
      RecipientType.police => Icons.local_police_outlined,
      RecipientType.platform => Icons.public,
      RecipientType.lawyer => Icons.gavel_outlined,
    };

/// The parent reports a case. The child's phone builds the case file at its next check-in;
/// "Keep my child anonymous" (on by default) leaves out everything that could identify them.
class ReportScreen extends StatefulWidget {
  const ReportScreen({super.key, required this.childId, required this.caseId});

  final String childId;
  final String caseId;

  @override
  State<ReportScreen> createState() => _ReportScreenState();
}

class _ReportScreenState extends State<ReportScreen> {
  RecipientType _recipient = RecipientType.school;
  bool _anonymous = true;
  bool _busy = false;
  String? _error;

  Future<void> _send() async {
    final session = ParentScope.of(context);
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final sent = context.t('guardian.report.sent');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await session.requestReport(widget.childId, widget.caseId, _recipient, anonymous: _anonymous);
      messenger.showSnackBar(SnackBar(content: Text(sent)));
      nav.pop(true);
    } catch (_) {
      if (mounted) setState(() => _error = context.t('guardian.account.offline'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final p = KuumaColors.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(context.t('guardian.report.title'))),
      body: ListView(padding: const EdgeInsets.only(bottom: KuumaSpace.xxl), children: [
        KuumaHero(
          icon: Icons.outgoing_mail,
          title: context.t('guardian.report.hero_title'),
          message: context.t('guardian.report.hero_body'),
        ),
        KuumaSectionHeader(context.t('guardian.report.to').toUpperCase()),
        for (final r in RecipientType.values)
          RadioListTile<RecipientType>(
            key: Key('report-to-${r.wire}'),
            value: r,
            groupValue: _recipient,
            secondary: Icon(recipientIcon(r)),
            title: Text(context.t('guardian.recipient.${r.wire}')),
            onChanged: (v) => setState(() => _recipient = v!),
          ),
        KuumaSectionHeader(context.t('guardian.report.privacy').toUpperCase()),
        Card(
          child: SwitchListTile(
            key: const Key('report-anonymous'),
            value: _anonymous,
            onChanged: (v) => setState(() => _anonymous = v),
            secondary: Icon(Icons.shield_outlined, color: p.accent),
            title: Text(context.t('guardian.report.anonymous')),
            subtitle: Text(context.t(_anonymous ? 'guardian.report.anonymous_on' : 'guardian.report.anonymous_off')),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.xl, vertical: KuumaSpace.sm),
          child: KuumaSteps(context.t('guardian.report.what_happens').split('\n')),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.xl),
            child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
          ),
        Padding(
          padding: const EdgeInsets.all(KuumaSpace.lg),
          child: KuumaButton(
            key: const Key('report-send'),
            icon: Icons.send_outlined,
            label: context.t('guardian.report.send'),
            onPressed: _busy ? null : _send,
          ),
        ),
      ]),
    );
  }
}

/// Reports in the Requests tab: waiting for the child's phone, ready (copy the link), or failed.
class ReportsSection extends StatelessWidget {
  const ReportsSection({super.key});

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    if (session.reports.isEmpty) return const SizedBox.shrink();
    final p = KuumaColors.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      KuumaSectionHeader(context.t('guardian.report.yours').toUpperCase()),
      for (final r in session.reports)
        KuumaCard(
          key: Key('report-${r.reportId}'),
          leading: Icon(recipientIcon(r.recipientType)),
          title: context.t('guardian.report.card_title', {
            'recipient': context.t('guardian.recipient.${r.recipientType.wire}'),
            'name': childName(context, session.child(r.childId)),
          }),
          subtitle: [
            if (r.anonymous) context.t('guardian.report.anonymous_short'),
            shortTime(context, r.createdAt),
          ].join(' · '),
          trailing: KuumaPill(
            label: context.t('guardian.report.status_${r.status.wire}'),
            color: switch (r.status) {
              ReportStatus.ready => p.accent,
              ReportStatus.failed => p.high,
              ReportStatus.requested => p.muted,
            },
          ),
          child: switch (r.status) {
            ReportStatus.ready => _ReadyActions(report: r),
            ReportStatus.requested => Text(context.t('guardian.report.waiting')),
            ReportStatus.failed => Text(context.t('guardian.report.failed')),
          },
        ),
    ]);
  }
}

class _ReadyActions extends StatelessWidget {
  const _ReadyActions({required this.report});

  final Report report;

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(context.t('guardian.report.ready_body')),
      const SizedBox(height: KuumaSpace.sm),
      KuumaButton(
        key: Key('report-copy-${report.reportId}'),
        kind: KuumaButtonKind.secondary,
        icon: Icons.link,
        label: context.t('guardian.report.copy'),
        onPressed: () async {
          final messenger = ScaffoldMessenger.of(context);
          final copied = context.t('guardian.report.copied');
          final failed = context.t('guardian.report.cannot_open');
          final link = await session.openReportLink(report);
          if (link == null) {
            messenger.showSnackBar(SnackBar(content: Text(failed)));
            return;
          }
          await Clipboard.setData(ClipboardData(text: link));
          messenger.showSnackBar(SnackBar(content: Text(copied)));
        },
      ),
    ]);
  }
}
