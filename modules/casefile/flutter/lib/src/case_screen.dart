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
  List<Map<Object?, Object?>> _shares = const [];
  final Set<int> _revealed = {};
  String? _notice;
  String? _link; // share link, after a report
  String? _download; // content URI in Downloads, after Save as PDF
  bool? _intact;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final t = await widget.vault.call<List<Object?>>('timeline', {'caseId': widget.caseId});
    final shares = await widget.vault.call<List<Object?>>('shares', {'caseId': widget.caseId});
    if (mounted) {
      setState(() {
        _timeline = (t ?? const []).cast<Map<Object?, Object?>>();
        _shares = (shares ?? const []).cast<Map<Object?, Object?>>();
      });
    }
  }

  Future<void> _run(Future<void> Function() work) async {
    setState(() => _busy = true);
    try {
      await work();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _check() => _run(() async {
        final r = await widget.vault.call<Map<Object?, Object?>>('verify');
        if (!mounted || r == null) return;
        setState(() {
          _intact = r['intact'] == true;
          _notice = context.t(_intact! ? 'casefile.check.ok' : 'casefile.check.bad');
        });
      });

  Future<void> _export() => _run(() async {
        final r = await widget.vault
            .call<Map<Object?, Object?>>('export', {'caseId': widget.caseId, 'recipientType': 'lawyer'});
        if (!mounted || r == null) return;
        final download = r['downloadUri'] as String?;
        setState(() {
          _intact = null;
          _link = null;
          _download = download;
          _notice = context.t(download == null ? 'casefile.export.done' : 'casefile.export.downloaded');
        });
        await _load(); // translations made for the file now show in the timeline
        if (download != null) await _open();
      });

  Future<void> _open() async {
    final ok = await widget.vault.call<bool>('openFile', {'uri': _download});
    if (ok != true && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.t('casefile.export.no_viewer'))));
    }
  }

  /// The phone's share menu with the link, so the report goes straight to the recipient.
  Future<void> _send() => widget.vault.call<bool>('sendLink', {
        'text': context.t('casefile.share.message', {'link': _link!}),
        'title': context.t('casefile.share.send'),
      });

  Future<void> _share() async {
    final recipient = await showModalBottomSheet<RecipientType>(
      context: context,
      builder: (c) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(KuumaSpace.xl, 0, KuumaSpace.xl, KuumaSpace.sm),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(c.t('casefile.share.title'), style: Theme.of(c).textTheme.titleLarge),
            ),
          ),
          for (final r in RecipientType.values)
            ListTile(
              leading: Icon(_recipientIcon(r)),
              title: Text(c.t('casefile.recipient.${r.wire}')),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(c).pop(r),
            ),
          const SizedBox(height: KuumaSpace.sm),
        ]),
      ),
    );
    if (recipient == null) return;
    await _run(() async {
      final requestId =
          await widget.vault.call<String>('requestShare', {'caseId': widget.caseId, 'recipientType': recipient.wire});
      final shares = await widget.vault.call<List<Object?>>('shares', {'caseId': widget.caseId}) ?? const [];
      if (!mounted) return;
      final share = shares.cast<Map<Object?, Object?>>().where((s) => s['requestId'] == requestId).firstOrNull;
      final link = share?['status'] == 'completed' ? (share?['link'] as String?) : null;
      setState(() {
        _intact = null;
        _download = null;
        _link = link;
        _notice = switch (share?['status']) {
          'pending_approval' => context.t('casefile.share.waiting'),
          'built_offline' => context.t('casefile.share.offline'),
          _ => context.t('casefile.share.ready'),
        };
      });
      await _load();
      // Report directly: open the share menu straight away (WhatsApp, SMS, email...).
      if (link != null) await _send();
    });
  }

  static IconData _recipientIcon(RecipientType r) => switch (r) {
        RecipientType.school => Icons.school_outlined,
        RecipientType.police => Icons.local_police_outlined,
        RecipientType.platform => Icons.public,
        RecipientType.lawyer => Icons.gavel_outlined,
      };

  @override
  Widget build(BuildContext context) {
    final p = KuumaColors.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(widget.label)),
      body: ListView(padding: const EdgeInsets.only(bottom: KuumaSpace.xxl), children: [
        if (_busy) const LinearProgressIndicator(),
        if (_notice != null)
          KuumaCard(
            title: _notice!,
            leading: Icon(
              _intact == false ? Icons.gpp_maybe_outlined : Icons.verified_outlined,
              color: _intact == false ? p.high : p.accent,
            ),
            child: _link == null && _download == null
                ? null
                : Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    if (_link != null) ...[
                      SelectableText(_link!, style: Theme.of(context).textTheme.bodySmall),
                      const SizedBox(height: KuumaSpace.sm),
                      KuumaButton(
                          key: const Key('send-link'),
                          label: context.t('casefile.share.send'),
                          icon: Icons.send_outlined,
                          onPressed: _send),
                    ],
                    if (_download != null)
                      KuumaButton(
                          key: const Key('open-pdf'),
                          label: context.t('casefile.export.open'),
                          icon: Icons.open_in_new,
                          kind: KuumaButtonKind.secondary,
                          onPressed: _open),
                  ]),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(KuumaSpace.lg, KuumaSpace.sm, KuumaSpace.lg, 0),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            KuumaButton(
                label: context.t('casefile.action.share'), icon: Icons.ios_share, onPressed: _busy ? null : _share),
            const SizedBox(height: KuumaSpace.sm),
            Row(children: [
              Expanded(
                child: KuumaButton(
                    label: context.t('casefile.action.check'),
                    icon: Icons.verified_outlined,
                    onPressed: _busy ? null : _check,
                    kind: KuumaButtonKind.secondary),
              ),
              const SizedBox(width: KuumaSpace.sm),
              Expanded(
                child: KuumaButton(
                    label: context.t('casefile.action.export'),
                    icon: Icons.picture_as_pdf_outlined,
                    onPressed: _busy ? null : _export,
                    kind: KuumaButtonKind.secondary),
              ),
            ]),
          ]),
        ),
        // The child always sees who this case was sent to, including reports their parent made.
        for (final s in _shares) _shareNote(context, s),
        KuumaSectionHeader(context.t('casefile.case.timeline').toUpperCase()),
        for (final (i, e) in _timeline.indexed) _entry(context, i, e),
      ]),
    );
  }

  Widget _shareNote(BuildContext context, Map<Object?, Object?> s) {
    final p = KuumaColors.of(context);
    final recipient = context.t('casefile.recipient.${s['recipientType']}');
    final byParent = s['reportedByParent'] as String?;
    return KuumaCard(
      leading: Icon(byParent == null ? Icons.ios_share : Icons.family_restroom, color: p.brand),
      title: byParent == null
          ? context.t('casefile.share.sent_to', {'recipient': recipient})
          : context.t('casefile.share.parent_reported', {'recipient': recipient}),
      subtitle: byParent == 'anonymous' ? context.t('casefile.share.anonymous') : null,
    );
  }

  String _when(Map<Object?, Object?> e) {
    final at = DateTime.fromMillisecondsSinceEpoch(e['at'] as int).toLocal();
    final l = MaterialLocalizations.of(context);
    return '${l.formatMediumDate(at)} · ${l.formatTimeOfDay(TimeOfDay.fromDateTime(at))}';
  }

  Widget _entry(BuildContext context, int i, Map<Object?, Object?> e) {
    final theme = Theme.of(context);
    final p = KuumaColors.of(context);
    if (e['kind'] == 'FLAG') {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.xl, vertical: KuumaSpace.xs),
        child: Row(children: [
          Icon(Icons.flag_outlined, size: 18, color: p.muted),
          const SizedBox(width: KuumaSpace.sm),
          Expanded(
            child: Text('${context.t('casefile.case.flagged')} · ${e['category'] ?? ''}',
                style: theme.textTheme.bodySmall),
          ),
          if (e['severity'] is String) SeverityBadge(Severity.fromWire(e['severity'] as String)),
        ]),
      );
    }
    final fromChild = e['fromChild'] == true;
    final shown = fromChild || _revealed.contains(i);
    final translation = e['translation'] as String?;
    final bubble = Container(
      constraints: const BoxConstraints(maxWidth: 520),
      padding: const EdgeInsets.all(KuumaSpace.md),
      decoration: BoxDecoration(
        color: fromChild ? p.surfaceMuted : p.surface,
        border: Border.all(color: p.border),
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(KuumaRadius.md),
          topRight: const Radius.circular(KuumaRadius.md),
          bottomLeft: Radius.circular(fromChild ? KuumaRadius.md : 4),
          bottomRight: Radius.circular(fromChild ? 4 : KuumaRadius.md),
        ),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(fromChild ? context.t('casefile.case.you') : '${e['sender'] ?? ''}',
            style: theme.textTheme.labelMedium?.copyWith(color: p.muted)),
        const SizedBox(height: KuumaSpace.xs),
        if (shown)
          Text(e['text'] as String? ?? '', style: theme.textTheme.bodyLarge)
        else
          Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(Icons.visibility_outlined, size: 18, color: p.primary),
            const SizedBox(width: KuumaSpace.xs),
            Flexible(
              child: Text(context.t('casefile.case.tap_to_show'),
                  style: theme.textTheme.bodyMedium?.copyWith(color: p.primary, fontWeight: FontWeight.w600)),
            ),
          ]),
        if (shown && translation != null) ...[
          const SizedBox(height: KuumaSpace.sm),
          Text(context.t('casefile.case.machine_translation'),
              style: theme.textTheme.labelSmall?.copyWith(color: p.muted)),
          Text(translation, style: theme.textTheme.bodyMedium?.copyWith(fontStyle: FontStyle.italic)),
        ],
        const SizedBox(height: KuumaSpace.xs),
        Text(_when(e), style: theme.textTheme.labelSmall),
      ]),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: KuumaSpace.lg, vertical: KuumaSpace.xs),
      child: Align(
        alignment: fromChild ? Alignment.centerRight : Alignment.centerLeft,
        child: Semantics(
          button: !shown,
          label: shown ? null : context.t('casefile.case.tap_to_show'),
          child: InkWell(
            borderRadius: BorderRadius.circular(KuumaRadius.md),
            onTap: shown ? null : () => setState(() => _revealed.add(i)),
            child: bubble,
          ),
        ),
      ),
    );
  }
}
