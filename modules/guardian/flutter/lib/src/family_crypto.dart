import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as hash;
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:pointycastle/export.dart';

/// base64url without padding, as in core-android's Base64Url.
String b64url(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

Uint8List b64urlDecode(String text) => base64Url.decode(base64Url.normalize(text));

Uint8List randomBytes(int n, [Random? random]) {
  final r = random ?? Random.secure();
  return Uint8List.fromList(List.generate(n, (_) => r.nextInt(256)));
}

/// The family key: 32 random bytes this phone makes when the parent adds a child. It leaves
/// the phone only inside the pairing QR code, so the server can neither read alert excerpts
/// nor forge a policy or an approval.
///
/// The same formulas are in `FamilyCrypto.kt` on the child's phone; shared test vectors keep
/// the two in step.
class FamilyCrypto {
  FamilyCrypto(this.key) {
    if (key.length != 32) throw ArgumentError('family key must be 32 bytes');
  }

  factory FamilyCrypto.generate([Random? random]) => FamilyCrypto(randomBytes(32, random));

  final Uint8List key;

  String sign(String message) => b64url(hash.Hmac(hash.sha256, key).convert(utf8.encode(message)).bytes);

  bool verify(String message, String? signature) {
    if (signature == null) return false;
    final a = sign(message).codeUnits;
    final b = signature.codeUnits;
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  Policy signPolicy(Policy p) => Policy(
        ageBracket: p.ageBracket,
        watchedApps: p.watchedApps,
        thresholds: p.thresholds,
        policyVersion: p.policyVersion,
        signature: sign(policyMessage(p)),
      );

  bool verifyPolicy(Policy p) => verify(policyMessage(p), p.signature);

  String signDecision(String approvalId, String requestId, ApprovalDecision decision) =>
      sign(decisionMessage(approvalId, requestId, decision.wire));

  /// base64url(12-byte IV || AES-256-GCM ciphertext and tag).
  String seal(String text, [Random? random]) {
    final iv = randomBytes(12, random);
    return b64url([...iv, ..._gcm(true, iv).process(Uint8List.fromList(utf8.encode(text)))]);
  }

  /// Opens a sealed excerpt. Throws if it was not sealed with this key or was altered.
  String open(String sealed) {
    final raw = b64urlDecode(sealed);
    if (raw.length < 12 + 16) throw const FormatException('too short');
    return utf8.decode(_gcm(false, raw.sublist(0, 12)).process(raw.sublist(12)));
  }

  GCMBlockCipher _gcm(bool encrypt, Uint8List iv) =>
      GCMBlockCipher(AESEngine())..init(encrypt, AEADParameters(KeyParameter(key), 128, iv, Uint8List(0)));

  static String policyMessage(Policy p) => [
        p.ageBracket.wire,
        (p.watchedApps.map((a) => a.wire).toList()..sort()).join(','),
        p.thresholds.flagMinConfidence.toStringAsFixed(3),
        p.thresholds.alertMinSeverity.wire,
        p.thresholds.captureMinSeverity.wire,
        '${p.policyVersion}',
      ].join('|');

  String signReport(String reportId, String caseId, RecipientType recipient, bool anonymous) =>
      sign(reportMessage(reportId, caseId, recipient.wire, anonymous));

  /// What the parent signs to report a case (same formula in FamilyCrypto.kt).
  static String reportMessage(String reportId, String caseId, String recipient, bool anonymous) =>
      'report|$reportId|$caseId|$recipient|$anonymous';

  /// Not decidedAt: the server stamps that after the parent signs.
  static String decisionMessage(String approvalId, String requestId, String decision) =>
      '$approvalId|$requestId|$decision';
}

/// A random version-4 UUID (report ids are made on the parent's phone so it can sign them).
String newUuid([Random? random]) {
  final b = randomBytes(16, random);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// The pairing QR payload: `kuuma-pair:1:<6-digit code>:<base64url family key>`.
class PairingQr {
  PairingQr(this.code, this.familyKey);

  final String code;
  final Uint8List familyKey;

  static final _format = RegExp(r'^kuuma-pair:1:([0-9]{6}):([A-Za-z0-9_-]{43})$');

  String get text => 'kuuma-pair:1:$code:${b64url(familyKey)}';

  static PairingQr? parse(String text) {
    final m = _format.firstMatch(text.trim());
    return m == null ? null : PairingQr(m.group(1)!, b64urlDecode(m.group(2)!));
  }
}

/// This phone's P-256 key, registered with the server to bind the parent account to it.
class DeviceKey {
  DeviceKey._(this.privateScalar, this.publicSpki);

  /// Big-endian private scalar, kept in the vault.
  final Uint8List privateScalar;

  /// X.509 SubjectPublicKeyInfo (DER), what `devicePublicKey` carries in base64.
  final Uint8List publicSpki;

  static final _spkiPrefix = Uint8List.fromList([
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, //
    0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
  ]);

  static DeviceKey generate([Random? random]) {
    final rng = FortunaRandom()..seed(KeyParameter(randomBytes(32, random)));
    final gen = ECKeyGenerator()..init(ParametersWithRandom(ECKeyGeneratorParameters(ECCurve_secp256r1()), rng));
    final pair = gen.generateKeyPair();
    final pub = pair.publicKey as ECPublicKey;
    final d = (pair.privateKey as ECPrivateKey).d!;
    final scalar = Uint8List.fromList(List.generate(32, (i) => ((d >> (8 * (31 - i))) & BigInt.from(0xff)).toInt()));
    return DeviceKey._(scalar, Uint8List.fromList([..._spkiPrefix, ...pub.Q!.getEncoded(false)]));
  }

  String get publicKeyBase64 => base64.encode(publicSpki);
}
