import 'dart:async';

import 'package:kuuma_contracts/kuuma_contracts.dart';

import 'bridge.dart';

/// In-memory bridge for widget tests and for running the screens without a phone.
/// Records every call so tests can check what would have been published.
class FakeCompanionBridge implements CompanionBridge {
  FakeCompanionBridge({List<AdvicePlanView>? recent, this.captureAvailable = true}) : _recent = [...?recent];

  final List<AdvicePlanView> _recent;
  final _advice = StreamController<AdvicePlanView>.broadcast();
  bool captureAvailable;

  final List<String> calls = [];

  /// Simulates a flag arriving from Sentinel.
  void push(AdvicePlanView plan) {
    _recent.insert(0, plan);
    _advice.add(plan);
  }

  @override
  Stream<AdvicePlanView> get advice => _advice.stream;

  @override
  Future<List<AdvicePlanView>> recent() async => List.of(_recent);

  @override
  Future<CrisisContent> crisis() async => sampleCrisis;

  @override
  Future<void> submitFeedback(String flagId, FeedbackVerdict verdict, {required bool consentToTrain}) async =>
      calls.add('feedback:$flagId:${verdict.wire}:$consentToTrain');

  @override
  Future<void> adviceShown(String flagId, String adviceKey) async => calls.add('shown:$flagId:$adviceKey');

  @override
  Future<bool> requestCapture() async {
    calls.add('capture');
    return captureAvailable;
  }

  @override
  Future<String> triggerSos() async {
    calls.add('sos');
    return '33333333-3333-4333-8333-333333333333';
  }

  @override
  Future<void> dial(String number) async => calls.add('dial:$number');

  static const sampleCrisis = CrisisContent(
    title: 'You matter',
    body: "You don't have to face it alone.",
    breathe: 'Breathe in slowly for 4.',
    callPrompt: 'Talk to someone now',
    helplines: [Helpline(id: 'sauti', number: '116', name: 'Sauti child helpline', free: true)],
  );

  static AdvicePlanView samplePlan({
    String flagId = '11111111-1111-4111-8111-111111111111',
    HarmCategory category = HarmCategory.insult,
    Severity severity = Severity.medium,
    bool crisis = false,
  }) =>
      AdvicePlanView(
        flagId: flagId,
        category: category,
        severity: severity,
        appSource: AppSource.whatsapp,
        crisis: crisis,
        cards: const [
          AdviceCardView(key: 'advice.general.do_not_reply', text: "You don't have to reply."),
          AdviceCardView(
            key: 'advice.general.save_evidence',
            text: 'Kuuma keeps a safe copy.',
            steps: ["Don't delete the messages."],
          ),
        ],
        blockSteps: const ['Tap the name.', 'Tap Block.'],
        reportSteps: const ['Tap Report.'],
      );
}
