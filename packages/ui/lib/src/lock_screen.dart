import 'package:flutter/material.dart';
import 'package:local_auth/local_auth.dart';

import 'l10n.dart';

/// Biometric unlock, wrapped so tests and devices without a sensor can supply their own.
abstract class BiometricAuth {
  Future<bool> available();
  Future<bool> authenticate(String reason);
}

/// local_auth-backed biometrics. Requires the host activity to be a FlutterFragmentActivity.
class LocalBiometricAuth implements BiometricAuth {
  final _auth = LocalAuthentication();

  @override
  Future<bool> available() async {
    try {
      return await _auth.canCheckBiometrics && await _auth.isDeviceSupported();
    } catch (_) {
      return false; // older phones without a sensor or API
    }
  }

  @override
  Future<bool> authenticate(String reason) async {
    try {
      return await _auth.authenticate(
          localizedReason: reason, options: const AuthenticationOptions(biometricOnly: true));
    } catch (_) {
      return false;
    }
  }
}

/// PIN and fingerprint lock. The PIN itself is checked by [verifyPin], which the app backs
/// with a salted hash in its own encrypted storage; this widget never stores the PIN.
class LockScreen extends StatefulWidget {
  const LockScreen({super.key, required this.verifyPin, required this.onUnlocked, this.biometrics, this.pinLength = 4});

  final Future<bool> Function(String pin) verifyPin;
  final VoidCallback onUnlocked;
  final BiometricAuth? biometrics;
  final int pinLength;

  @override
  State<LockScreen> createState() => _LockScreenState();
}

class _LockScreenState extends State<LockScreen> {
  String _entered = '';
  bool _wrong = false;
  bool _bioAvailable = false;

  @override
  void initState() {
    super.initState();
    widget.biometrics?.available().then((ok) {
      if (mounted) setState(() => _bioAvailable = ok);
    });
  }

  Future<void> _press(String digit) async {
    if (_entered.length >= widget.pinLength) return;
    setState(() {
      _entered += digit;
      _wrong = false;
    });
    if (_entered.length == widget.pinLength) {
      final ok = await widget.verifyPin(_entered);
      if (!mounted) return;
      if (ok) {
        widget.onUnlocked();
      } else {
        setState(() {
          _entered = '';
          _wrong = true;
        });
      }
    }
  }

  Future<void> _biometric() async {
    final ok = await widget.biometrics!.authenticate(context.t('core.lock.fingerprint_reason'));
    if (ok && mounted) widget.onUnlocked();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    Widget key(String d) => SizedBox(
          width: 72,
          height: 72,
          child: TextButton(
            key: Key('pin-$d'),
            onPressed: () => _press(d),
            child: Text(d, style: theme.textTheme.headlineSmall),
          ),
        );
    return Scaffold(
      body: SafeArea(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(context.t('core.lock.title'), style: theme.textTheme.titleLarge),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(
                widget.pinLength,
                (i) => Padding(
                  padding: const EdgeInsets.all(6),
                  child: Icon(i < _entered.length ? Icons.circle : Icons.circle_outlined, size: 16),
                ),
              ),
            ),
            SizedBox(
              height: 24,
              child: _wrong
                  ? Text(context.t('core.lock.wrong_pin'), style: TextStyle(color: theme.colorScheme.error))
                  : null,
            ),
            for (final row in const [
              ['1', '2', '3'],
              ['4', '5', '6'],
              ['7', '8', '9'],
            ])
              Row(mainAxisAlignment: MainAxisAlignment.center, children: row.map(key).toList()),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              SizedBox(
                width: 72,
                height: 72,
                child: _bioAvailable
                    ? IconButton(
                        tooltip: context.t('core.lock.use_fingerprint'),
                        icon: const Icon(Icons.fingerprint),
                        onPressed: _biometric,
                      )
                    : null,
              ),
              key('0'),
              SizedBox(
                width: 72,
                height: 72,
                child: IconButton(
                  icon: const Icon(Icons.backspace_outlined),
                  onPressed: () =>
                      setState(() => _entered = _entered.isEmpty ? '' : _entered.substring(0, _entered.length - 1)),
                ),
              ),
            ]),
          ],
        ),
      ),
    );
  }
}
