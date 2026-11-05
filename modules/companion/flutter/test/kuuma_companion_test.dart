import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_companion/kuuma_companion.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

Widget _app(Widget home) => MaterialApp(home: Scaffold(body: home));

void main() {
  late FakeCompanionBridge bridge;

  setUp(() => bridge = FakeCompanionBridge());

  testWidgets('registers the Help tab, and the fake screen only in debug', (tester) async {
    final release = SlotRegistry();
    CompanionFlutterModule(bridge: bridge, debug: false).register(release, KuumaL10n.instance);
    expect([for (final s in KuumaSlot.all) ...release.entries(s).map((e) => e.id)], ['companion.home']);

    final debug = SlotRegistry();
    CompanionFlutterModule(bridge: bridge, debug: true).register(debug, KuumaL10n.instance);
    expect(debug.entries(KuumaSlot.childSettings).map((e) => e.id), ['companion.debug']);
  });

  testWidgets('home is calm with no flags, then lists a flag as it arrives', (tester) async {
    CompanionFlutterModule(bridge: bridge, debug: false).register(SlotRegistry(), KuumaL10n.instance);
    await tester.pumpWidget(_app(CompanionHomeScreen(bridge: bridge)));
    await tester.pumpAndSettle();
    expect(find.text("You're doing okay"), findsOneWidget);

    bridge.push(FakeCompanionBridge.samplePlan(category: HarmCategory.threat, severity: Severity.high));
    await tester.pumpAndSettle();
    expect(find.text('Threat'), findsOneWidget);
    expect(find.byType(SeverityBadge), findsOneWidget);
  });

  testWidgets('a self-harm flag opens the crisis screen at once, and the helpline dials', (tester) async {
    CompanionFlutterModule(bridge: bridge, debug: false).register(SlotRegistry(), KuumaL10n.instance);
    await tester.pumpWidget(_app(CompanionHomeScreen(bridge: bridge)));
    await tester.pumpAndSettle();

    bridge.push(FakeCompanionBridge.samplePlan(category: HarmCategory.selfHarmEncouragement, crisis: true));
    await tester.pumpAndSettle();
    expect(find.byType(CrisisScreen), findsOneWidget);
    expect(find.text('You matter'), findsOneWidget);

    await tester.tap(find.byKey(const Key('companion-call-sauti')));
    expect(bridge.calls, contains('dial:116'));
  });

  testWidgets('advice screen reports cards shown and sends "this isn\'t abuse" without consent by default',
      (tester) async {
    CompanionFlutterModule(bridge: bridge, debug: false).register(SlotRegistry(), KuumaL10n.instance);
    final plan = FakeCompanionBridge.samplePlan();
    await tester.pumpWidget(MaterialApp(home: AdviceScreen(plan: plan, bridge: bridge)));
    await tester.pumpAndSettle();

    expect(find.text("You don't have to reply."), findsOneWidget);
    expect(bridge.calls.where((c) => c.startsWith('shown:')).toList(), [
      'shown:${plan.flagId}:advice.general.do_not_reply',
      'shown:${plan.flagId}:advice.general.save_evidence',
    ]);

    await tester.scrollUntilVisible(find.byKey(const Key('companion-not-abuse')), 100);
    await tester.tap(find.byKey(const Key('companion-not-abuse')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('companion-feedback-send')));
    await tester.pumpAndSettle();
    expect(bridge.calls.last, 'feedback:${plan.flagId}:not_harmful:false');
    expect(find.text('Thanks for telling us.'), findsOneWidget);
  });

  testWidgets('the fake screen publishes capture, SOS and feedback on demand', (tester) async {
    CompanionFlutterModule(bridge: bridge, debug: false).register(SlotRegistry(), KuumaL10n.instance);
    await tester.pumpWidget(_app(CompanionDebugScreen(bridge: bridge)));
    for (final label in [
      'Publish companion.capture.requested',
      'Publish companion.sos.triggered',
      'Publish companion.feedback.submitted',
    ]) {
      await tester.tap(find.text(label));
      await tester.pump();
    }
    expect(bridge.calls, ['capture', 'sos', 'feedback:${CompanionDebugScreen.sampleFlagId}:not_harmful:false']);
  });

  test('plans decode from the maps the Kotlin bridge sends', () {
    final plan = AdvicePlanView.fromMap({
      'flagId': 'f',
      'category': 'grooming',
      'severity': 'high',
      'appSource': 'sms',
      'language': 'lg',
      'crisis': false,
      'cards': [
        {
          'key': 'advice.grooming.secrets_are_a_warning',
          'text': 'x',
          'steps': ['a', 'b']
        },
      ],
      'blockSteps': ['b'],
      'reportSteps': <String>[],
    });
    expect(plan.category, HarmCategory.grooming);
    expect(plan.cards.single.steps, ['a', 'b']);
  });
}
