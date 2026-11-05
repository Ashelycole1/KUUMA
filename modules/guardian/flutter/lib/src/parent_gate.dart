import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'api.dart';
import 'session.dart';

/// Makes the parent session reachable from every Guardian screen.
class ParentScope extends InheritedNotifier<ParentSession> {
  const ParentScope({super.key, required ParentSession session, required super.child}) : super(notifier: session);

  static ParentSession of(BuildContext context) => context.dependOnInheritedWidgetOfExactType<ParentScope>()!.notifier!;
}

/// Wraps the whole parent app: first run creates the account, then every start (and every
/// return from the background) asks for the PIN or fingerprint.
class ParentGate extends StatefulWidget {
  const ParentGate({super.key, required this.session, required this.child, this.biometrics});

  final ParentSession session;
  final Widget child;
  final BiometricAuth? biometrics;

  @override
  State<ParentGate> createState() => _ParentGateState();
}

class _ParentGateState extends State<ParentGate> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (widget.session.state == SessionState.loading) widget.session.load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Someone else picking up the phone must not see the child's alerts.
    if ((state == AppLifecycleState.hidden || state == AppLifecycleState.paused) &&
        widget.session.state == SessionState.unlocked) {
      widget.session.lock();
    }
  }

  @override
  Widget build(BuildContext context) => ParentScope(
        session: widget.session,
        child: ListenableBuilder(
          listenable: widget.session,
          builder: (context, _) => switch (widget.session.state) {
            SessionState.unlocked => widget.child,
            final state => _own(
                state,
                switch (state) {
                  SessionState.needsAccount => CreateAccountScreen(session: widget.session),
                  SessionState.locked => LockScreen(
                      verifyPin: widget.session.verifyPin,
                      onUnlocked: widget.session.unlock,
                      biometrics: widget.biometrics,
                    ),
                  _ => const Scaffold(body: Center(child: CircularProgressIndicator())),
                }),
          },
        ),
      );
}

/// The gate can sit above the app's navigator (so locking hides every open screen); its own
/// screens then get a navigator of their own for text fields and dialogs.
Widget _own(SessionState state, Widget screen) => Navigator(
      key: ValueKey(state),
      onGenerateRoute: (_) => MaterialPageRoute<void>(builder: (_) => screen),
    );

class CreateAccountScreen extends StatefulWidget {
  const CreateAccountScreen({super.key, required this.session});

  final ParentSession session;

  @override
  State<CreateAccountScreen> createState() => _CreateAccountScreenState();
}

class _CreateAccountScreenState extends State<CreateAccountScreen> {
  final _pin = TextEditingController();
  final _confirm = TextEditingController();
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _pin.dispose();
    _confirm.dispose();
    super.dispose();
  }

  bool get _ready => _pin.text.length == 4 && _confirm.text.length == 4;

  Future<void> _create() async {
    if (_pin.text != _confirm.text) {
      setState(() {
        _error = context.t('guardian.account.pin_mismatch');
        _confirm.clear();
      });
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.session.createAccount(_pin.text);
    } on GuardianApiException {
      if (mounted) setState(() => _error = context.t('guardian.account.offline'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _field(Key key, TextEditingController c, String label) => TextField(
        key: key,
        controller: c,
        obscureText: true,
        keyboardType: TextInputType.number,
        maxLength: 4,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        decoration: InputDecoration(labelText: label, counterText: ''),
        onChanged: (_) => setState(() {}),
      );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: SafeArea(
        child: ListView(padding: const EdgeInsets.all(24), children: [
          const SizedBox(height: 24),
          Icon(Icons.family_restroom, size: 56, color: theme.colorScheme.primary),
          const SizedBox(height: 16),
          Text(context.t('guardian.account.welcome_title'),
              style: theme.textTheme.headlineSmall, textAlign: TextAlign.center),
          const SizedBox(height: 8),
          Text(context.t('guardian.account.welcome_body'), textAlign: TextAlign.center),
          const SizedBox(height: 24),
          _field(const Key('pin-new'), _pin, context.t('guardian.account.choose_pin')),
          _field(const Key('pin-confirm'), _confirm, context.t('guardian.account.confirm_pin')),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(_error!, style: TextStyle(color: theme.colorScheme.error)),
            ),
          const SizedBox(height: 24),
          KuumaButton(label: context.t('guardian.account.create'), onPressed: _ready && !_busy ? _create : null),
        ]),
      ),
    );
  }
}

/// Shown at the top of a parent screen when the last refresh failed.
class OfflineBanner extends StatelessWidget {
  const OfflineBanner({super.key});

  @override
  Widget build(BuildContext context) {
    final session = ParentScope.of(context);
    if (!session.offline) return const SizedBox.shrink();
    return MaterialBanner(
      content: Text(context.t('guardian.common.offline_banner')),
      actions: [TextButton(onPressed: session.refresh, child: Text(context.t('guardian.common.retry')))],
    );
  }
}
