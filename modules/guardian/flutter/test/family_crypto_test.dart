import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';

/// The same vectors as GuardianEngineTest.kt, so the parent's and child's phones agree.
final vectorKey = Uint8List.fromList(List.generate(32, (i) => (i * 7 + 3) & 0xff));

const vectorPolicy = Policy(
  ageBracket: AgeBracket.agePlaceholderB,
  watchedApps: [AppSource.whatsapp, AppSource.sms],
  thresholds:
      Thresholds(flagMinConfidence: 0.5, alertMinSeverity: Severity.medium, captureMinSeverity: Severity.medium),
  policyVersion: 1,
);

void main() {
  final crypto = FamilyCrypto(vectorKey);

  test('shared vectors match the Kotlin side', () {
    expect(b64url(vectorKey), 'AwoRGB8mLTQ7QklQV15lbHN6gYiPlp2kq7K5wMfO1dw');
    expect(FamilyCrypto.policyMessage(vectorPolicy), 'age_placeholder_b|sms,whatsapp|0.500|medium|medium|1');
    expect(crypto.signPolicy(vectorPolicy).signature, 'HTf5k54Ky4WK7qoii-xrLoP0AsX-OyhchvkcHdP0p-Y');
    expect(crypto.open('AQIDBAUGBwgJCgsMHkkPkEcmYMuc3ecpeDg1Z9Np9K2c4ZXLkL4QaXM_'), 'Nkuba ku ssimu');
  });

  test('policies verify only unchanged and with the right key', () {
    final signed = crypto.signPolicy(vectorPolicy);
    expect(crypto.verifyPolicy(signed), isTrue);
    final tampered = Policy(
        ageBracket: signed.ageBracket,
        watchedApps: const [AppSource.sms],
        thresholds: signed.thresholds,
        policyVersion: 1,
        signature: signed.signature);
    expect(crypto.verifyPolicy(tampered), isFalse);
    expect(FamilyCrypto.generate().verifyPolicy(signed), isFalse);
  });

  test('seal and open round trip, and altered text fails', () {
    final sealed = crypto.seal('Ojja kulaba!');
    expect(crypto.open(sealed), 'Ojja kulaba!');
    final raw = b64urlDecode(sealed)..[20] ^= 1;
    expect(() => crypto.open(b64url(raw)), throwsA(anything));
    expect(() => FamilyCrypto.generate().open(sealed), throwsA(anything));
  });

  test('decision signatures leave out decidedAt', () {
    expect(FamilyCrypto.decisionMessage('a', 'r', 'approve'), 'a|r|approve');
    expect(crypto.signDecision('a', 'r', ApprovalDecision.approve), crypto.sign('a|r|approve'));
  });

  test('pairing QR round trip and rejects other text', () {
    final qr = PairingQr('042917', vectorKey);
    final back = PairingQr.parse(qr.text)!;
    expect(back.code, '042917');
    expect(back.familyKey, vectorKey);
    expect(PairingQr.parse('https://example.org'), isNull);
    expect(PairingQr.parse('kuuma-pair:1:12345:${b64url(vectorKey)}'), isNull);
  });

  test('device key is an uncompressed P-256 SPKI', () {
    final k = DeviceKey.generate();
    expect(k.publicSpki.length, 91);
    expect(k.privateScalar.length, 32);
    expect(k.publicSpki[26], 0x04);
  });
}
