import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:pointycastle/export.dart';

import 'api.dart';
import 'family_crypto.dart';

/// The parent phone's secrets: session token, PIN hash, device key and one family key per child.
abstract class GuardianVault {
  Future<String?> read(String key);
  Future<void> write(String key, String? value);
}

/// Android Keystore-backed storage (flutter_secure_storage).
class SecureGuardianVault implements GuardianVault {
  const SecureGuardianVault();

  static const _storage = FlutterSecureStorage();

  @override
  Future<String?> read(String key) => _storage.read(key: 'guardian.$key');

  @override
  Future<void> write(String key, String? value) =>
      value == null ? _storage.delete(key: 'guardian.$key') : _storage.write(key: 'guardian.$key', value: value);
}

class MemoryGuardianVault implements GuardianVault {
  final Map<String, String> values = {};

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String? value) async => value == null ? values.remove(key) : values[key] = value;
}

/// Defaults per age group (12 and under, 13 to 15, 16 to 18). Younger children get more sensitive
/// settings. PLACEHOLDER values until the team agrees the thresholds themselves
/// (Guardian brief, "Decisions you need"). Younger children get more sensitive settings.
Thresholds defaultThresholds(AgeBracket bracket) => switch (bracket) {
      AgeBracket.age12Under =>
        const Thresholds(flagMinConfidence: 0.4, alertMinSeverity: Severity.low, captureMinSeverity: Severity.low),
      AgeBracket.age1315 => const Thresholds(
          flagMinConfidence: 0.5, alertMinSeverity: Severity.medium, captureMinSeverity: Severity.medium),
      AgeBracket.age1618 =>
        const Thresholds(flagMinConfidence: 0.6, alertMinSeverity: Severity.medium, captureMinSeverity: Severity.high),
    };

/// A child as the parent's phone sees it: the server's status plus what only this phone knows
/// (the nickname and the full policy). Nicknames never leave the phone.
class ChildView {
  ChildView({required this.status, required this.nickname, required this.policy});

  final ChildStatus status;
  final String nickname;
  final Policy? policy;

  String get childId => status.childId;
}

/// What the parent sees after "Add a child": the QR code the child's phone scans.
class PendingPairing {
  PendingPairing(this.childId, this.qr, this.expiresAt);

  final String childId;
  final PairingQr qr;
  final DateTime expiresAt;
}

enum SessionState { loading, needsAccount, locked, unlocked }

/// The parent's account on this phone: a random ID bound to this phone's key, unlocked by PIN
/// or fingerprint. Holds the family keys, so it signs policies and decisions and opens excerpts.
class ParentSession extends ChangeNotifier {
  ParentSession({required this.api, GuardianVault? vault, this.pinIterations = 20000})
      : vault = vault ?? const SecureGuardianVault();

  final GuardianParentApi api;
  final GuardianVault vault;
  final int pinIterations;

  SessionState state = SessionState.loading;
  List<ChildView> children = const [];
  List<Alert> alerts = const [];
  List<Approval> approvals = const [];

  /// Set when the last refresh could not reach the server (screens show cached data).
  bool offline = false;

  Future<void> load() async {
    final token = await vault.read('token');
    api.token = token;
    state = token == null || await vault.read('pin') == null ? SessionState.needsAccount : SessionState.locked;
    notifyListeners();
  }

  // ------------------------------------------------------------------ account and PIN

  Future<void> createAccount(String pin) async {
    final key = DeviceKey.generate();
    final r = await api.register(key.publicKeyBase64);
    await vault.write('deviceKey', b64url(key.privateScalar));
    await vault.write('parentId', r.parentId);
    await vault.write('token', r.sessionToken);
    api.token = r.sessionToken;
    await _setPin(pin);
    state = SessionState.unlocked;
    notifyListeners();
    await refresh();
  }

  Future<void> _setPin(String pin) async {
    final salt = randomBytes(16);
    await vault.write('pin', '${b64url(salt)}:${b64url(_hashPin(pin, salt))}');
  }

  Uint8List _hashPin(String pin, Uint8List salt) {
    final kdf = PBKDF2KeyDerivator(HMac(SHA256Digest(), 64))..init(Pbkdf2Parameters(salt, pinIterations, 32));
    return kdf.process(Uint8List.fromList(utf8.encode(pin)));
  }

  Future<bool> verifyPin(String pin) async {
    final stored = await vault.read('pin');
    if (stored == null) return false;
    final parts = stored.split(':');
    final expected = parts[1];
    final actual = b64url(_hashPin(pin, b64urlDecode(parts[0])));
    var diff = expected.length ^ actual.length;
    for (var i = 0; i < expected.length && i < actual.length; i++) {
      diff |= expected.codeUnitAt(i) ^ actual.codeUnitAt(i);
    }
    return diff == 0;
  }

  Future<void> unlock() async {
    state = SessionState.unlocked;
    notifyListeners();
    await refresh();
  }

  void lock() {
    state = SessionState.locked;
    alerts = const [];
    notifyListeners();
  }

  // ------------------------------------------------------------------ data

  String get _token => api.token ?? (throw GuardianApiException(401, 'signed_out'));

  Future<FamilyCrypto?> crypto(String childId) async {
    final k = await vault.read('child.$childId.key');
    return k == null ? null : FamilyCrypto(b64urlDecode(k));
  }

  Future<void> refresh() async {
    if (state != SessionState.unlocked) return;
    try {
      final statuses = await api.children(_token);
      final views = <ChildView>[];
      for (final s in statuses) {
        final policyJson = await vault.read('child.${s.childId}.policy');
        views.add(ChildView(
          status: s,
          nickname: await vault.read('child.${s.childId}.name') ?? '',
          policy: policyJson == null ? null : Policy.fromJson(jsonDecode(policyJson) as Map<String, dynamic>),
        ));
      }
      children = views;
      alerts = await api.alerts(_token);
      approvals = await api.approvals(_token);
      offline = false;
    } on GuardianApiException catch (e) {
      if (!e.offline) rethrow;
      offline = true;
    }
    notifyListeners();
  }

  ChildView? child(String? childId) {
    for (final c in children) {
      if (c.childId == childId) return c;
    }
    return null;
  }

  /// Starts adding a child: makes the family key, signs the first policy, gets a pairing code.
  Future<PendingPairing> addChild({
    required String nickname,
    required AgeBracket bracket,
    required List<AppSource> watchedApps,
    Severity? alertMinSeverity,
  }) async {
    final family = FamilyCrypto.generate();
    final policy = family.signPolicy(Policy(
      ageBracket: bracket,
      watchedApps: watchedApps,
      thresholds: _thresholds(bracket, alertMinSeverity),
      policyVersion: 1,
    ));
    final r = await api.createPairingCode(policy);
    await vault.write('child.${r.childId}.key', b64url(family.key));
    await vault.write('child.${r.childId}.name', nickname.trim());
    await vault.write('child.${r.childId}.policy', jsonEncode(policy.toJson()));
    await refresh();
    return PendingPairing(r.childId, PairingQr(r.pairingCode, family.key), DateTime.parse(r.expiresAt));
  }

  /// Signs and sends a new policy version. The child's phone only accepts it with this signature.
  Future<Policy> updatePolicy(
    String childId, {
    required AgeBracket bracket,
    required List<AppSource> watchedApps,
    Severity? alertMinSeverity,
  }) async {
    final family = await crypto(childId) ?? (throw StateError('no family key for this child'));
    final current = child(childId)?.status.policyVersion ?? 1;
    final policy = family.signPolicy(Policy(
      ageBracket: bracket,
      watchedApps: watchedApps,
      thresholds: _thresholds(bracket, alertMinSeverity),
      policyVersion: current + 1,
    ));
    await api.putPolicy(_token, childId, policy);
    await vault.write('child.$childId.policy', jsonEncode(policy.toJson()));
    await refresh();
    return policy;
  }

  Future<void> rename(String childId, String nickname) async {
    await vault.write('child.$childId.name', nickname.trim());
    await refresh();
  }

  static Thresholds _thresholds(AgeBracket bracket, Severity? alertMin) {
    final d = defaultThresholds(bracket);
    return Thresholds(
        flagMinConfidence: d.flagMinConfidence,
        alertMinSeverity: alertMin ?? d.alertMinSeverity,
        captureMinSeverity: d.captureMinSeverity);
  }

  /// Opens an alert's sealed excerpt with that child's family key. Null if there is none or
  /// it cannot be opened. The text is shown on screen only, never stored or logged.
  Future<String?> openExcerpt(Alert alert) async {
    final sealed = alert.sealedExcerpt;
    final childId = alert.childId;
    if (sealed == null || childId == null) return null;
    try {
      return (await crypto(childId))?.open(sealed);
    } catch (_) {
      return null;
    }
  }

  /// Signs the decision with the family key so the child's phone can trust it.
  /// Uses the family key of the child the request came from (Approval.childId).
  Future<void> decide(Approval approval, ApprovalDecision decision) async {
    final childId = approval.childId ?? (children.length == 1 ? children.single.childId : null);
    final family = childId == null ? null : await crypto(childId);
    if (family == null) throw StateError('no family key for this request');
    final signature = family.signDecision(approval.approvalId, approval.requestId, decision);
    await api.decide(_token, approval.approvalId, decision, signature);
    await refresh();
  }
}
