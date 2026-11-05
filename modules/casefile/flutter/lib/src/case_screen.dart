import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

/// One case: the cross-app timeline, an evidence check, PDF export and sharing.
///
/// Message text starts hidden (tap to reveal), so opening the app in front of someone
/// does not put abuse on screen. TODO(casefile): vary what is shown by age bracket once
/// the brackets are agreed (they are placeholders today).
class CaseScreen extends StatefulWidget {
  const CaseScreen({super.key, required this.vault, required this.caseId, required this.label});

  final ModuleChannel vault;
  final String caseId;
  final String label;

  @override
  State<CaseScreen> createState() => _CaseScreenState();
}

class _CaseScreenState extends State<CaseScreen> {
  List<Map<Object?, Object?>> _timeline = const [];
  final Set<int> _revealed = {};
  String? _notice;

  @override
  void initState() {
    super.initState();
    widget.vault.call<List<Object?>>('timeline', {'caseId': widget.caseId}).then((t) {
      if (mounted) setState(() => _timeline = (t ?? const []).cast<Map<Object?, Object?>>());
    });
  }

  Future<void> _check() async {
    final r = await widget.vault.call<Map<Object?, Object?>>('verify');
    if (!mounted || r == null) return;
    setState(() => _notice = context.t(r['intact'] == true ? 'casefile.check.ok' : 'casefile.check.bad'));
  }

  Future<void> _export() async {
    final r =
        await widget.vault.call<Map<Object?, Object?>>('export', {'caseId': widget.caseId, 'recipientType': 'lawyer'});
    if (!mounted || r == null) return;
    setState(() => _notice = context.t('casefile.export.done'));
  }

  Future<void> _share() async {
    final recipient = await showModalBottomSheet<RecipientType>(
      context: context,
      builder: (c) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(title: Text(c.t('casefile.share.title'), style: Theme.of(c).textTheme.titleMedium)),
          for (final r in RecipientType.values)
            ListTile(title: Text(c.t('casefile.recipient.${r.wire}')), onTap: () => Navigator.of(c).pop(r)),
        ]),
      ),
    );
    if (recipient == null) return;
    final requestId =
        await widget.vault.call<String>('requestShare', {'caseId': widget.caseId, 'recipientType': recipient.wire});
    final shares = await widget.vault.call<List<Object?>>('shares', {'caseId': widget.caseId}) ?? const [];
    if (!mounted) return;
    final share = shares.cast<Map<Object?, Object?>>().where((s) => s['requestId'] == requestId).firstOrNull;
    setState(() => _notice = switch (share?['status']) {
          'pending_approval' => context.t('casefile.share.waiting'),
          'built_offline' => context.t('casefile.share.offline'),
          _ => '${context.t('casefile.share.ready')}\n${share?['link'] ?? ''}',
        });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.label)),
      body: ListView(padding: const EdgeInsets.only(bottom: 24), children: [
        if (_notice != null) KuumaCard(title: _notice!, leading: const Icon(Icons.info_outline)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Wrap(spacing: 8, runSpacing: 8, children: [
            KuumaButton(
                label: context.t('casefile.action.check'),
                icon: Icons.verified_outlined,
                onPressed: _check,
                kind: KuumaButtonKind.secondary),
            KuumaButton(
                label: context.t('casefile.action.export'),
                icon: Icons.picture_as_pdf_outlined,
                onPressed: _export,
                kind: KuumaButtonKind.secondary),
            KuumaButton(label: context.t('casefile.action.share'), icon: Icons.share_outlined, onPressed: _share),
          ]),
        ),
        ListTile(title: Text(context.t('casefile.case.timeline'), style: Theme.of(context).textTheme.titleMedium)),
        for (final (i, e) in _timeline.indexed) _entry(context, i, e),
      ]),
    );
  }

  Widget _entry(BuildContext context, int i, Map<Object?, Object?> e) {
    final at = DateTime.fromMillisecondsSinceEpoch(e['at'] as int).toLocal();
    final when = '${at.year}-${at.month.toString().padLeft(2, '0')}-${at.day.toString().padLeft(2, '0')} '
        '${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')}';
    if (e['kind'] == 'FLAG') {
      return ListTile(
        dense: true,
        leading: const Icon(Icons.flag_outlined),
        title: Text(context.t('casefile.case.flagged')),
        subtitle: Text('$when · ${e['appSource']} · ${e['category']}'),
        trailing: e['severity'] is String ? SeverityBadge(Severity.fromWire(e['severity'] as String)) : null,
      );
    }
    final fromChild = e['fromChild'] == true;
    final shown = fromChild || _revealed.contains(i);
    return ListTile(
      leading: Icon(fromChild ? Icons.call_made : Icons.call_received),
      title: Text(shown ? (e['text'] as String? ?? '') : context.t('casefile.case.tap_to_show')),
      subtitle: Text('$when · ${fromChild ? context.t('casefile.case.you') : e['sender'] ?? ''}'),
      onTap: shown ? null : () => setState(() => _revealed.add(i)),
    );
  }
}
