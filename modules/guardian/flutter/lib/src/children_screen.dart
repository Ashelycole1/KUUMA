import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_lang/kuuma_lang.dart';
import 'package:kuuma_ui/kuuma_ui.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'parent_gate.dart';
import 'session.dart';
import 'strings.dart';

/// How long without a check-in before a phone counts as silent. Matches the service's TAMPER_AFTER.
const silentAfter = Duration(minutes: 60);

enum ProtectionStatus { waiting, active, missing, silent }

ProtectionStatus protectionOf(ChildStatus s, DateTime now) {
  if (!s.paired) return ProtectionStatus.waiting;
  if (s.protectionActive == false || (s.missingPermissions ?? const []).isNotEmpty) return ProtectionStatus.missing;
  final last = s.lastHeartbeatAt == null ? null : DateTime.tryParse(s.lastHeartbeatAt!);
  if (last == null || now.difference(last) > silentAfter) return ProtectionStatus.silent;
  return ProtectionStatus.active;
}

String shortTime(BuildContext context, String? iso) {
  final t = iso == null ? null : DateTime.tryParse(iso)?.toLocal();
  if (t == null) return '';
  return '${MaterialLocalizations.of(context).formatMediumDate(t)} '
      '${MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(t))}';
}

String childName(BuildContext context, ChildView? c) =>
    c == null || c.nickname.isEmpty ? context.t('guardian.common.unnamed') : c.nickname;

class ChildrenScreen extends StatelessWidget {
  const ChildrenScreen({super.key, this.now});

  final DateTime Function()? now;

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    final add = KuumaButton(
      key: const Key('add-child'),
      icon: Icons.add,
      label: context.t('guardian.children.add'),
      onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => ParentScope(session: session, child: const ChildPolicyScreen()),
      )),
    );
    if (session.children.isEmpty) {
      return Column(children: [
        const OfflineBanner(),
        Expanded(
          child: EmptyState(
            icon: Icons.family_restroom,
            title: context.t('guardian.children.empty_title'),
            message: context.t('guardian.children.empty_body'),
            action: add,
          ),
        ),
      ]);
    }
    final at = (now ?? DateTime.now)();
    return RefreshIndicator(
      onRefresh: session.refresh,
      child: ListView(padding: const EdgeInsets.all(8), children: [
        const OfflineBanner(),
        for (final c in session.children) _ChildCard(child: c, status: protectionOf(c.status, at)),
        Padding(padding: const EdgeInsets.all(16), child: add),
      ]),
    );
  }
}

class _ChildCard extends StatelessWidget {
  const _ChildCard({required this.child, required this.status});

  final ChildView child;
  final ProtectionStatus status;

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    final (icon, color, text) = switch (status) {
      ProtectionStatus.active => (
          Icons.verified_user_outlined,
          KuumaColors.of(context).low,
          context.t('guardian.status.active')
        ),
      ProtectionStatus.waiting => (
          Icons.qr_code,
          Theme.of(context).colorScheme.outline,
          context.t('guardian.status.waiting')
        ),
      ProtectionStatus.missing => (
          Icons.gpp_bad_outlined,
          KuumaColors.of(context).high,
          context.t('guardian.status.missing')
        ),
      ProtectionStatus.silent => (
          Icons.portable_wifi_off,
          KuumaColors.of(context).medium,
          child.status.lastHeartbeatAt == null
              ? context.t('guardian.status.never')
              : context.t('guardian.status.silent', {'time': shortTime(context, child.status.lastHeartbeatAt)})
        ),
    };
    final missing = child.status.missingPermissions ?? const [];
    return KuumaCard(
      leading: Icon(icon, color: color),
      title: childName(context, child),
      subtitle: text,
      trailing: const Icon(Icons.chevron_right),
      onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => ParentScope(session: session, child: ChildPolicyScreen(childId: child.childId)),
      )),
      child: missing.isEmpty
          ? null
          : Text('${context.t('guardian.child.missing_list')} ${missing.map(permissionLabel).join(', ')}'),
    );
  }
}

/// Permission names as a parent understands them.
String permissionLabel(String permission) => switch (permission) {
      'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' => 'Notification access',
      'android.permission.BIND_ACCESSIBILITY_SERVICE' => 'Accessibility',
      'android.permission.BIND_INPUT_METHOD' => 'Keyboard',
      'android.permission.POST_NOTIFICATIONS' => 'Notifications',
      'android.permission.CAMERA' => 'Camera',
      _ => permission.split('.').last.replaceAll('_', ' ').toLowerCase(),
    };

/// Add a child (no [childId]) or change a child's policy. The bracket sets the thresholds.
class ChildPolicyScreen extends StatefulWidget {
  const ChildPolicyScreen({super.key, this.childId});

  final String? childId;

  @override
  State<ChildPolicyScreen> createState() => _ChildPolicyScreenState();
}

class _ChildPolicyScreenState extends State<ChildPolicyScreen> {
  final _name = TextEditingController();
  AgeBracket _bracket = AgeBracket.age1315;
  final Set<AppSource> _apps = {AppSource.whatsapp, AppSource.sms};
  Severity _alertMin = Severity.medium;
  bool _busy = false;
  bool _loaded = false;
  String? _error;

  bool get _adding => widget.childId == null;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_loaded) return;
    _loaded = true;
    final c = ParentScope.of(context).child(widget.childId);
    if (c == null) return;
    _name.text = c.nickname;
    final p = c.policy;
    if (p != null) {
      _bracket = p.ageBracket;
      _apps
        ..clear()
        ..addAll(p.watchedApps);
      _alertMin = p.thresholds.alertMinSeverity;
    } else {
      _bracket = c.status.ageBracket;
    }
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final session = ParentScope.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final nav = Navigator.of(context);
    final saved = context.t('guardian.child.policy_saved');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final apps = AppSource.values.where(_apps.contains).toList();
      if (_adding) {
        final pending = await session.addChild(
            nickname: _name.text, bracket: _bracket, watchedApps: apps, alertMinSeverity: _alertMin);
        await nav.pushReplacement(MaterialPageRoute<void>(
          builder: (_) => ParentScope(session: session, child: PairingQrScreen(pending: pending)),
        ));
      } else {
        final c = session.child(widget.childId)!;
        if (_name.text.trim() != c.nickname) await session.rename(c.childId, _name.text);
        final p = c.policy;
        final changed = p == null ||
            p.ageBracket != _bracket ||
            p.thresholds.alertMinSeverity != _alertMin ||
            p.watchedApps.toSet().difference(_apps).isNotEmpty ||
            _apps.difference(p.watchedApps.toSet()).isNotEmpty;
        if (changed) {
          await session.updatePolicy(c.childId, bracket: _bracket, watchedApps: apps, alertMinSeverity: _alertMin);
          messenger.showSnackBar(SnackBar(content: Text(saved)));
        }
        nav.pop();
      }
    } catch (_) {
      if (mounted) setState(() => _error = context.t('guardian.account.offline'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final c = ParentScope.of(context).child(widget.childId);
    final title = _adding
        ? context.t('guardian.children.add')
        : context.t('guardian.child.policy_title', {'name': childName(context, c)});
    Widget heading(String key) => Padding(
          padding: const EdgeInsets.only(top: 20, bottom: 8),
          child: Text(context.t(key), style: theme.textTheme.titleSmall),
        );
    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        TextField(
          key: const Key('nickname'),
          controller: _name,
          maxLength: 40,
          decoration: InputDecoration(labelText: context.t('guardian.child.nickname')),
        ),
        heading('guardian.child.age'),
        for (final e in KuumaLang.entries('age-brackets'))
          RadioListTile<AgeBracket>(
            key: Key('bracket-${e.id}'),
            value: AgeBracket.fromWire(e.id),
            groupValue: _bracket,
            title: Text(e.label(Localizations.localeOf(context).languageCode)),
            onChanged: (v) => setState(() {
              _bracket = v!;
              _alertMin = defaultThresholds(v).alertMinSeverity;
            }),
          ),
        heading('guardian.child.apps'),
        Wrap(spacing: 8, children: [
          for (final a in AppSource.values)
            FilterChip(
              key: Key('app-${a.wire}'),
              label: Text(appLabel(a)),
              selected: _apps.contains(a),
              onSelected: (on) => setState(() => on ? _apps.add(a) : _apps.remove(a)),
            ),
        ]),
        heading('guardian.child.alert_level'),
        for (final s in Severity.values)
          RadioListTile<Severity>(
            key: Key('alert-${s.wire}'),
            value: s,
            groupValue: _alertMin,
            title: Text(context.t('guardian.child.alert_${s.wire}')),
            onChanged: (v) => setState(() => _alertMin = v!),
          ),
        Text(context.t('guardian.child.high_always'), style: theme.textTheme.bodySmall),
        if (c?.policy != null)
          Text(context.t('guardian.child.policy_version', {'n': '${c!.status.policyVersion}'}),
              style: theme.textTheme.bodySmall),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
          ),
        const SizedBox(height: 24),
        KuumaButton(
          key: const Key('save-child'),
          label: context.t(_adding ? 'guardian.child.next' : 'guardian.common.save'),
          onPressed: _busy || _apps.isEmpty ? null : _save,
        ),
      ]),
    );
  }
}

/// The QR code the child's phone scans. It carries the family key, so it is shown once.
class PairingQrScreen extends StatelessWidget {
  const PairingQrScreen({super.key, required this.pending});

  final PendingPairing pending;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(context.t('guardian.qr.title'))),
      body: ListView(padding: const EdgeInsets.all(24), children: [
        Text(context.t('guardian.qr.steps')),
        const SizedBox(height: 16),
        Center(
          child: Container(
            color: Colors.white,
            padding: const EdgeInsets.all(12),
            child: QrImageView(key: const Key('pairing-qr'), data: pending.qr.text, size: 240),
          ),
        ),
        const SizedBox(height: 12),
        Text(context.t('guardian.qr.code', {'code': pending.qr.code}),
            style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
        Text(
            context.t('guardian.qr.expires', {
              'time':
                  MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(pending.expiresAt.toLocal()))
            }),
            textAlign: TextAlign.center),
        const SizedBox(height: 12),
        Text(context.t('guardian.qr.private'), style: theme.textTheme.bodySmall, textAlign: TextAlign.center),
        // No camera to scan with (or both apps on one phone): copy the text instead.
        TextButton(
          key: const Key('copy-qr'),
          onPressed: () async {
            final messenger = ScaffoldMessenger.of(context);
            final copied = context.t('guardian.qr.copied');
            await Clipboard.setData(ClipboardData(text: pending.qr.text));
            messenger.showSnackBar(SnackBar(content: Text(copied)));
          },
          child: Text(context.t('guardian.qr.copy')),
        ),
        const SizedBox(height: 24),
        KuumaButton(
          label: context.t('guardian.qr.done'),
          onPressed: () {
            ParentScope.of(context).refresh();
            Navigator.of(context).pop();
          },
        ),
      ]),
    );
  }
}
