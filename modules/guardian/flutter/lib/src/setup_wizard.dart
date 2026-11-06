import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'children_screen.dart' show permissionLabel;
import 'family_crypto.dart';
import 'strings.dart';

/// Builds the camera view. Tests and the emulator pass their own.
typedef ScannerBuilder = Widget Function(BuildContext context, void Function(String code) onCode);

Widget cameraScanner(BuildContext context, void Function(String code) onCode) => MobileScanner(
      onDetect: (capture) {
        for (final b in capture.barcodes) {
          final v = b.rawValue;
          if (v != null && v.startsWith('kuuma-pair:')) {
            onCode(v);
            return;
          }
        }
      },
    );

enum WizardStep { intro, consent, scan, permissions, done }

/// The child phone's Guardian screen in Settings: the setup wizard until the phone is
/// paired, then a read-only "managed by your parent" view. Talks to GuardianModule (Kotlin).
class GuardianSetupScreen extends StatefulWidget {
  const GuardianSetupScreen({super.key, required this.channel, this.scanner = cameraScanner});

  final ModuleChannel channel;
  final ScannerBuilder scanner;

  @override
  State<GuardianSetupScreen> createState() => _GuardianSetupScreenState();
}

class _GuardianSetupScreenState extends State<GuardianSetupScreen> with WidgetsBindingObserver {
  Map<Object?, Object?>? _status;
  List<Map<Object?, Object?>> _permissions = const [];
  WizardStep _step = WizardStep.intro;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Back from Android settings: see what was turned on.
    if (state == AppLifecycleState.resumed) _loadPermissions();
  }

  bool get _paired => _status?['paired'] == true;

  Future<void> _load() async {
    final s = await widget.channel.call<Map<Object?, Object?>>('status') ?? const {};
    await _loadPermissions();
    if (!mounted) return;
    setState(() {
      _status = s;
      if (s['paired'] == true && _step != WizardStep.permissions) _step = WizardStep.done;
    });
  }

  Future<void> _loadPermissions() async {
    final list = await widget.channel.call<List<Object?>>('permissions') ?? const [];
    if (mounted) setState(() => _permissions = list.cast<Map<Object?, Object?>>());
  }

  Future<void> _consent() async {
    await widget.channel.call<Object?>('consent');
    setState(() => _step = WizardStep.scan);
  }

  Future<void> _pair(String code) async {
    if (_busy) return;
    if (PairingQr.parse(code) == null) {
      setState(() => _error = context.t('guardian.wizard.bad_code'));
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    final result = await widget.channel.call<String>('pair', {'qr': code});
    if (!mounted) return;
    setState(() {
      _busy = false;
      switch (result) {
        case 'PAIRED' || 'ALREADY_PAIRED':
          _step = WizardStep.permissions;
        case 'BAD_CODE':
          _error = context.t('guardian.wizard.expired_code');
        case 'BAD_POLICY_SIGNATURE':
          _error = context.t('guardian.wizard.bad_policy');
        default:
          _error = context.t('guardian.wizard.offline');
      }
    });
    if (_step == WizardStep.permissions) await _load();
  }

  Future<void> _manualCode() async {
    final code = await showDialog<String>(context: context, builder: (_) => const _CodeDialog());
    if (code != null && code.trim().isNotEmpty) await _pair(code.trim());
  }

  @override
  Widget build(BuildContext context) {
    if (_status == null) return const Center(child: CircularProgressIndicator());
    return switch (_step) {
      WizardStep.intro => _page(
          icon: Icons.family_restroom,
          title: 'guardian.wizard.intro_title',
          body: 'guardian.wizard.intro_body',
          action: KuumaButton(
              key: const Key('wizard-start'),
              label: context.t('guardian.wizard.start'),
              onPressed: () => setState(() => _step = WizardStep.consent)),
        ),
      WizardStep.consent => _page(
          icon: Icons.privacy_tip_outlined,
          title: 'guardian.wizard.consent_title',
          body: 'guardian.wizard.consent_body',
          action: KuumaButton(
              key: const Key('wizard-consent'), label: context.t('guardian.wizard.consent_agree'), onPressed: _consent),
        ),
      WizardStep.scan => _scanPage(),
      WizardStep.permissions => _permissionsPage(finish: true),
      WizardStep.done => _managedPage(),
    };
  }

  Widget _page({required IconData icon, required String title, required String body, Widget? action}) {
    final theme = Theme.of(context);
    return ListView(padding: const EdgeInsets.all(24), children: [
      Icon(icon, size: 56, color: theme.colorScheme.primary),
      const SizedBox(height: 16),
      Text(context.t(title), style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
      const SizedBox(height: 12),
      Text(context.t(body), textAlign: TextAlign.center),
      if (action != null) ...[const SizedBox(height: 24), action],
    ]);
  }

  Widget _scanPage() {
    final theme = Theme.of(context);
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text(context.t('guardian.wizard.scan_title'), style: theme.textTheme.titleLarge, textAlign: TextAlign.center),
      const SizedBox(height: 8),
      Text(context.t('guardian.wizard.scan_body'), textAlign: TextAlign.center),
      const SizedBox(height: 16),
      Center(
        child: SizedBox.square(
          dimension: 280,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: _busy ? const Center(child: CircularProgressIndicator()) : widget.scanner(context, _pair),
          ),
        ),
      ),
      if (_error != null)
        Padding(
          padding: const EdgeInsets.only(top: 12),
          child: Text(_error!,
              key: const Key('wizard-error'),
              style: TextStyle(color: theme.colorScheme.error),
              textAlign: TextAlign.center),
        ),
      const SizedBox(height: 12),
      // Debug builds only (emulators have no camera): paste the text the parent app copied.
      if (kDebugMode)
        TextButton(
            key: const Key('wizard-manual'), onPressed: _manualCode, child: Text(context.t('guardian.wizard.paste'))),
    ]);
  }

  Widget _permissionsPage({required bool finish}) {
    final theme = Theme.of(context);
    final requiredMissing = _permissions.any((p) => p['required'] == true && p['granted'] != true);
    return ListView(padding: const EdgeInsets.all(16), children: [
      Text(context.t('guardian.wizard.permissions_title'), style: theme.textTheme.titleLarge),
      const SizedBox(height: 8),
      Text(context.t('guardian.wizard.permissions_body')),
      const SizedBox(height: 12),
      ..._permissionTiles(),
      const SizedBox(height: 24),
      if (finish)
        KuumaButton(
          key: const Key('wizard-finish'),
          label: context.t('guardian.wizard.finish'),
          onPressed: requiredMissing
              ? null
              : () async {
                  await widget.channel.call<Object?>('heartbeat');
                  if (mounted) setState(() => _step = WizardStep.done);
                },
        ),
    ]);
  }

  List<Widget> _permissionTiles() => [
        for (final p in _permissions)
          KuumaCard(
            key: Key('perm-${p['permission']}'),
            leading: Icon(p['granted'] == true ? Icons.check_circle : Icons.radio_button_unchecked,
                color: p['granted'] == true ? KuumaColors.of(context).low : null),
            title: _reason(p),
            subtitle: p['required'] == true ? null : context.t('guardian.wizard.optional'),
            trailing: p['granted'] == true
                ? Text(context.t('guardian.wizard.granted'))
                : TextButton(
                    onPressed: () => widget.channel.call<Object?>('openSettings', {'permission': p['permission']}),
                    child: Text(context.t('guardian.wizard.turn_on')),
                  ),
          ),
      ];

  /// The owning module's reason, or a plain name if that module has no translation for it.
  String _reason(Map<Object?, Object?> p) {
    final key = p['reasonKey'] as String;
    final text = context.t(key);
    return text == key ? permissionLabel(p['permission'] as String) : text;
  }

  Widget _managedPage() {
    final theme = Theme.of(context);
    final s = _status!;
    final apps = (s['watchedApps'] as List<Object?>? ?? const [])
        .map((w) => appLabel(AppSource.fromWire(w as String)))
        .join(', ');
    final pending = s['pendingAlerts'] as int? ?? 0;
    final missing = _permissions.where((p) => p['required'] == true && p['granted'] != true).toList();
    return RefreshIndicator(
      // Pull down to check in now: reports protection, collects the parent's decisions.
      onRefresh: () async {
        await widget.channel.call<Object?>('heartbeat');
        await _load();
      },
      child: ListView(padding: const EdgeInsets.all(16), children: [
        KuumaCard(
          leading: Icon(missing.isEmpty ? Icons.verified_user_outlined : Icons.gpp_bad_outlined,
              color: missing.isEmpty ? KuumaColors.of(context).low : KuumaColors.of(context).high),
          title: context.t(_paired ? 'guardian.wizard.managed_title' : 'guardian.wizard.done_title'),
          subtitle: context.t('guardian.wizard.managed_body'),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            if (apps.isNotEmpty) Text(context.t('guardian.wizard.watching', {'apps': apps})),
            if (pending > 0) Text(context.t('guardian.wizard.pending', {'n': '$pending'})),
          ]),
        ),
        if (missing.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text(context.t('guardian.wizard.permissions_title'), style: theme.textTheme.titleMedium),
          ..._permissionTiles().where((w) => missing.any((p) => w.key == Key('perm-${p['permission']}'))),
        ],
      ]),
    );
  }
}

/// Debug builds only: the pairing text typed or pasted in by hand. Owns its controller so it
/// lives exactly as long as the dialog.
class _CodeDialog extends StatefulWidget {
  const _CodeDialog();

  @override
  State<_CodeDialog> createState() => _CodeDialogState();
}

class _CodeDialogState extends State<_CodeDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(context.t('guardian.wizard.paste')),
        content: TextField(
          key: const Key('manual-code'),
          controller: _controller,
          autofocus: true,
          decoration: InputDecoration(hintText: context.t('guardian.wizard.paste_hint')),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: Text(context.t('guardian.common.cancel'))),
          TextButton(
              key: const Key('manual-ok'),
              onPressed: () => Navigator.pop(context, _controller.text),
              child: Text(context.t('guardian.common.save'))),
        ],
      );
}
