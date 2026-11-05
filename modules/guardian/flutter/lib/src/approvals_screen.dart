import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'children_screen.dart';
import 'parent_gate.dart';

/// Casefile's share requests. Each decision is signed with the child's family key.
class ApprovalsScreen extends StatefulWidget {
  const ApprovalsScreen({super.key});

  @override
  State<ApprovalsScreen> createState() => _ApprovalsScreenState();
}

class _ApprovalsScreenState extends State<ApprovalsScreen> {
  final Set<String> _busy = {};

  Future<void> _decide(Approval a, ApprovalDecision d) async {
    final session = ParentScope.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final failed = context.t('guardian.account.offline');
    setState(() => _busy.add(a.approvalId));
    try {
      await session.decide(a, d);
    } catch (_) {
      messenger.showSnackBar(SnackBar(content: Text(failed)));
    } finally {
      if (mounted) setState(() => _busy.remove(a.approvalId));
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    final approvals = session.approvals;
    return RefreshIndicator(
      onRefresh: session.refresh,
      child: ListView(padding: const EdgeInsets.all(8), children: [
        const OfflineBanner(),
        if (approvals.isEmpty)
          SizedBox(
            height: 360,
            child: EmptyState(
              icon: Icons.task_alt,
              title: context.t('guardian.approvals.empty_title'),
              message: context.t('guardian.approvals.empty_body'),
            ),
          ),
        for (final a in approvals)
          KuumaCard(
            key: Key('approval-${a.approvalId}'),
            leading: const Icon(Icons.ios_share),
            title: childName(context, session.child(a.childId)),
            subtitle: [
              context.t('guardian.approvals.share',
                  {'recipient': context.t('guardian.recipient.${(a.recipientType ?? RecipientType.school).wire}')}),
              shortTime(context, a.createdAt),
            ].join(' · '),
            child: a.status == ApprovalStatus.pending
                ? Row(children: [
                    Expanded(
                      child: KuumaButton(
                        key: Key('decline-${a.approvalId}'),
                        kind: KuumaButtonKind.secondary,
                        label: context.t('guardian.approvals.decline'),
                        onPressed: _busy.contains(a.approvalId) ? null : () => _decide(a, ApprovalDecision.decline),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: KuumaButton(
                        key: Key('approve-${a.approvalId}'),
                        label: context.t('guardian.approvals.approve'),
                        onPressed: _busy.contains(a.approvalId) ? null : () => _decide(a, ApprovalDecision.approve),
                      ),
                    ),
                  ])
                : Text(context.t('guardian.approvals.${a.status.wire}')),
          ),
      ]),
    );
  }
}
