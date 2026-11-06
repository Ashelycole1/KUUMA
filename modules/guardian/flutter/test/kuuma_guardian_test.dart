import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

Widget app(Widget home) => MaterialApp(
      supportedLocales: kuumaLocales,
      localizationsDelegates: kuumaLocalizationsDelegates,
      home: home,
    );

Widget tab(ParentSession s, Widget screen) => app(ParentScope(session: s, child: Scaffold(body: screen)));

void tall(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 3200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

Future<(ParentSession, FakeGuardianParentApi)> unlocked() async {
  final api = FakeGuardianParentApi();
  final s = ParentSession(api: api, vault: MemoryGuardianVault(), pinIterations: 10);
  await s.load();
  await s.createAccount('2580');
  return (s, api);
}

void main() {
  setUpAll(() => GuardianFlutterModule().register(SlotRegistry(), KuumaL10n.instance));

  test('registers its screens in the right slots', () {
    final slots = SlotRegistry();
    // l10n is already registered once; registering again just re-adds the same strings.
    GuardianFlutterModule().register(slots, KuumaL10n.instance);
    expect(slots.entries(KuumaSlot.childSettings).map((e) => e.id), ['guardian.pairing']);
    expect(slots.entries(KuumaSlot.parentTab).map((e) => e.id),
        ['guardian.parent.alerts', 'guardian.parent.children', 'guardian.parent.approvals']);
    expect(KuumaL10n.instance.translate('guardian.guide.threat.talk', 'en'), isNot('guardian.guide.threat.talk'));
  });

  test('every alert kind and category has guidance', () {
    for (final c in HarmCategory.values) {
      for (final part in ['talk', 'avoid']) {
        final key = 'guardian.guide.${guideKey(AlertKind.flag, c)}.$part';
        expect(KuumaL10n.instance.translate(key, 'en'), isNot(key));
      }
    }
    for (final k in AlertKind.values) {
      final key = 'guardian.guide.${guideKey(k, null)}.talk';
      expect(KuumaL10n.instance.translate(key, 'en'), isNot(key));
    }
  });

  testWidgets('gate: create account, then lock and unlock with the PIN', (tester) async {
    final s = ParentSession(api: FakeGuardianParentApi(), vault: MemoryGuardianVault(), pinIterations: 10);
    await tester.pumpWidget(app(ParentGate(session: s, child: const Text('inside'))));
    await tester.pumpAndSettle();
    expect(find.text('Kuuma for parents'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('pin-new')), '2580');
    await tester.enterText(find.byKey(const Key('pin-confirm')), '1111');
    await tester.pump();
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.textContaining('did not match'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('pin-confirm')), '2580');
    await tester.pump();
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text('inside'), findsOneWidget);

    for (final st in [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]) {
      tester.binding.handleAppLifecycleStateChanged(st);
    }
    expect(s.state, SessionState.locked);
    for (final st in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
      tester.binding.handleAppLifecycleStateChanged(st);
    }
    await tester.pumpAndSettle();
    expect(find.text('inside'), findsNothing);
    for (final d in '2580'.split('')) {
      await tester.tap(find.byKey(Key('pin-$d')));
      await tester.pump();
    }
    await tester.pumpAndSettle();
    expect(find.text('inside'), findsOneWidget);
  });

  testWidgets('children: add a child shows the pairing QR, then the status', (tester) async {
    tall(tester);
    final (s, api) = await unlocked();
    await tester.pumpWidget(tab(s, const ChildrenScreen()));
    expect(find.text('No children yet'), findsOneWidget);
    await tester.tap(find.byKey(const Key('add-child')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('nickname')), 'Amara');
    await tester.tap(find.byKey(const Key('app-tiktok')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('save-child')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('pairing-qr')), findsOneWidget);
    final childId = api.policies.keys.single;
    expect(api.policies[childId]!.watchedApps, [AppSource.whatsapp, AppSource.sms, AppSource.tiktok]);
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
    expect(find.text('Amara'), findsOneWidget);
    expect(find.text('Waiting to pair'), findsOneWidget);

    api.pairChild(childId, protectionActive: false, missing: ['android.permission.BIND_ACCESSIBILITY_SERVICE']);
    await s.refresh();
    await tester.pumpAndSettle();
    expect(find.text('Protection turned off'), findsOneWidget);
    expect(find.textContaining('Accessibility'), findsOneWidget);
  });

  testWidgets('children: changing settings sends a new signed version', (tester) async {
    tall(tester);
    final (s, api) = await unlocked();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    api.pairChild(p.childId);
    await s.refresh();
    await tester.pumpWidget(tab(s, const ChildrenScreen()));
    expect(find.text('Protected'), findsOneWidget);
    await tester.tap(find.text('Amara'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('alert-high')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('save-child')));
    await tester.pumpAndSettle();
    expect(api.policies[p.childId]!.policyVersion, 2);
    expect(api.policies[p.childId]!.thresholds.alertMinSeverity, Severity.high);
    expect(FamilyCrypto(p.qr.familyKey).verifyPolicy(api.policies[p.childId]!), isTrue);
  });

  testWidgets('alerts: trend, guidance, and the message only on request', (tester) async {
    final (s, api) = await unlocked();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final sealed = FamilyCrypto(p.qr.familyKey).seal('Ojja kulaba!');
    api.addAlert(p.childId, sealedExcerpt: sealed);
    api.addAlert(p.childId, kind: AlertKind.tamper, severity: Severity.medium);
    await s.refresh();
    await tester.pumpWidget(tab(s, const AlertsScreen()));
    expect(find.text('Last 7 days'), findsOneWidget);
    expect(find.text('Threat'), findsOneWidget);
    expect(find.text('Protection changed'), findsOneWidget);
    await tester.tap(find.text('Threat'));
    await tester.pumpAndSettle();
    expect(find.text('How to talk about it'), findsOneWidget);
    expect(find.text('What to avoid'), findsOneWidget);
    expect(find.text('Ojja kulaba!'), findsNothing);
    await tester.tap(find.text('Show the message'));
    await tester.pumpAndSettle();
    expect(find.text('Ojja kulaba!'), findsOneWidget);
    await tester.tap(find.text('Hide the message'));
    await tester.pumpAndSettle();
    expect(find.text('Ojja kulaba!'), findsNothing);
  });

  testWidgets('approvals: approve signs and sends the decision', (tester) async {
    final (s, api) = await unlocked();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final req = api.addApproval(p.childId, recipient: RecipientType.police);
    await s.refresh();
    await tester.pumpWidget(tab(s, const ApprovalsScreen()));
    expect(find.textContaining('Police'), findsOneWidget);
    await tester.tap(find.byKey(Key('approve-${req.approvalId}')));
    await tester.pumpAndSettle();
    expect(find.text('Approved'), findsOneWidget);
    expect(api.calls.last, startsWith('decide:approve:'));
  });

  testWidgets('report: from an alert, anonymous by default, link ready in Requests', (tester) async {
    tall(tester);
    final (s, api) = await unlocked();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    api.pairChild(p.childId);
    final alert = api.addAlert(p.childId);
    await s.refresh();
    await tester.pumpWidget(tab(s, AlertDetailScreen(alert: alert)));
    await tester.tap(find.byKey(const Key('report-case')));
    await tester.pumpAndSettle();
    expect(find.text('Keep my child anonymous'), findsOneWidget);
    await tester.tap(find.byKey(const Key('report-to-police')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('report-send')));
    await tester.pumpAndSettle();
    expect(api.calls.last, 'createReport:police:true');
    final id = api.reportRequests.keys.single;

    await tester.pumpWidget(tab(s, const ApprovalsScreen()));
    await tester.pumpAndSettle();
    expect(find.text('Waiting'), findsOneWidget);
    api.completeReport(id, FamilyCrypto(p.qr.familyKey).seal('http://kuuma/verify/s/x#k=secret'));
    await s.refresh();
    await tester.pumpAndSettle();
    expect(find.text('Ready'), findsOneWidget);
    expect(find.byKey(Key('report-copy-$id')), findsOneWidget);
  });

  testWidgets('tamper alerts have nothing to report', (tester) async {
    final (s, api) = await unlocked();
    final p = await s.addChild(nickname: 'Amara', bracket: AgeBracket.age1315, watchedApps: [AppSource.sms]);
    final alert = api.addAlert(p.childId, kind: AlertKind.tamper, severity: Severity.medium);
    await s.refresh();
    await tester.pumpWidget(tab(s, AlertDetailScreen(alert: alert)));
    expect(find.byKey(const Key('report-case')), findsNothing);
  });

  group('child setup wizard', () {
    const channel = MethodChannel('ug.kuuma/module/guardian');
    late Map<String, Object?> status;
    late List<Map<String, Object?>> perms;
    late List<String> calls;

    setUp(() {
      status = {'paired': false, 'pendingAlerts': 0};
      perms = [
        {
          'permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
          'module': 'sentinel',
          'reasonKey': 'guardian.permission.notifications.reason',
          'kind': 'SPECIAL',
          'required': true,
          'granted': false,
        },
      ];
      calls = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        switch (call.method) {
          case 'status':
            return status;
          case 'permissions':
            return perms;
          case 'pair':
            final ok = PairingQr.parse((call.arguments as Map)['qr'] as String)!.code == '123456';
            if (ok) {
              status = {
                'paired': true,
                'watchedApps': ['whatsapp'],
                'pendingAlerts': 0
              };
            }
            return ok ? 'PAIRED' : 'BAD_CODE';
          case 'openSettings':
            perms = [
              {...perms.single, 'granted': true}
            ];
            return true;
        }
        return null;
      });
    });

    tearDown(() =>
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null));

    final key = b64url(List.filled(32, 9));
    void Function(String)? scan;
    Widget fakeScanner(BuildContext context, void Function(String) onCode) {
      scan = onCode;
      return const ColoredBox(color: Colors.black);
    }

    testWidgets('consent, scan, permissions, done', (tester) async {
      await tester.pumpWidget(
          app(Scaffold(body: GuardianSetupScreen(channel: ModuleChannel('guardian'), scanner: fakeScanner))));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-start')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-consent')));
      await tester.pumpAndSettle();
      expect(calls, contains('consent'));

      scan!('not a code');
      await tester.pumpAndSettle();
      expect(find.text('That is not a Kuuma pairing code.'), findsOneWidget);
      scan!('kuuma-pair:1:999999:$key');
      await tester.pumpAndSettle();
      expect(find.textContaining('expired'), findsOneWidget);
      scan!('kuuma-pair:1:123456:$key');
      await tester.pumpAndSettle();

      expect(find.text('Turn on protection'), findsOneWidget);
      expect(find.text('Notification access'), findsNothing); // the reason key has a translation here
      final finish = tester.widget<FilledButton>(
          find.descendant(of: find.byKey(const Key('wizard-finish')), matching: find.byType(FilledButton)));
      expect(finish.onPressed, isNull); // required permission still off
      await tester.tap(find.text('Turn on'));
      for (final st in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed
      ]) {
        tester.binding.handleAppLifecycleStateChanged(st);
      }
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-finish')));
      await tester.pumpAndSettle();
      expect(calls, contains('heartbeat'));
      expect(find.text('Managed by your parent'), findsOneWidget);
      expect(find.textContaining('WhatsApp'), findsOneWidget);
    });

    testWidgets('debug builds can pair by pasting the code text', (tester) async {
      await tester.pumpWidget(
          app(Scaffold(body: GuardianSetupScreen(channel: ModuleChannel('guardian'), scanner: fakeScanner))));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-start')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-consent')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-manual')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel')); // closing must not break anything
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('wizard-manual')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('manual-code')), 'kuuma-pair:1:123456:$key');
      await tester.tap(find.byKey(const Key('manual-ok')));
      await tester.pumpAndSettle();
      expect(find.text('Turn on protection'), findsOneWidget);
    });

    testWidgets('a paired phone shows only the managed view', (tester) async {
      status = {
        'paired': true,
        'watchedApps': ['sms'],
        'pendingAlerts': 2
      };
      await tester.pumpWidget(
          app(Scaffold(body: GuardianSetupScreen(channel: ModuleChannel('guardian'), scanner: fakeScanner))));
      await tester.pumpAndSettle();
      expect(find.text('Managed by your parent'), findsOneWidget);
      expect(find.text('2 alerts waiting to be sent'), findsOneWidget);
      expect(find.text('Turn on'), findsOneWidget); // missing permission is offered
      expect(find.byKey(const Key('wizard-start')), findsNothing);
    });
  });
}
