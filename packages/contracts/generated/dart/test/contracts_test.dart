import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_contracts/testing.dart';
import 'package:test/test.dart';

const ref = 'or1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

Envelope<SentinelMessageFlaggedPayload> flagged() => const Envelope(
      eventId: '3f1c6a2e-8b7d-4c1e-9a2b-1d2e3f4a5b6c',
      type: 'sentinel.message.flagged',
      version: 1,
      occurredAt: '2026-11-05T09:30:00Z',
      deviceId: '7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b',
      payload: SentinelMessageFlaggedPayload(
        flagId: 'f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c',
        messageRef: ref,
        appSource: AppSource.whatsapp,
        conversationRef: ref,
        senderRef: ref,
        category: HarmCategory.insult,
        severity: Severity.high,
        confidence: 0.9,
        language: Language.lg,
      ),
    );

void main() {
  test('20 events', () => expect(Events.all, hasLength(20)));

  test('valid event passes and round-trips', () {
    final json = flagged().toJson();
    assertPublishes('sentinel', json);
    final back = Envelope.fromJson(json);
    expect(back.payload, isA<SentinelMessageFlaggedPayload>());
    expect(back.toJson(), json);
  });

  test('wrong publisher fails', () {
    expect(() => assertPublishes('guardian', flagged().toJson()), throwsA(isA<ContractViolation>()));
  });

  test('alias patterns work', () {
    expect(ContractPatterns.opaqueRef.hasMatch(ref), isTrue);
    expect(ContractPatterns.adviceKey.hasMatch('advice.threat.tell_adult'), isTrue);
    expect(ContractPatterns.timestamp.hasMatch('2026-11-05T09:30:00Z'), isTrue);
    expect(ContractPatterns.opaqueRef.hasMatch('not-a-ref'), isFalse);
  });

  test('phone-only payloads are typed as such', () {
    expect(Events.sentinelCaptureReady.phoneOnly, isTrue);
  });
}
