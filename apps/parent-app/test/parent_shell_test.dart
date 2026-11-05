import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_parent_app/main.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  testWidgets('account creation leads to Guardian\'s registered tabs', (tester) async {
    final slots = SlotRegistry();
    final guardian = GuardianFlutterModule(
        session: ParentSession(api: FakeGuardianParentApi(), vault: MemoryGuardianVault(), pinIterations: 10));
    guardian.register(slots, KuumaL10n.instance);
    await tester.pumpWidget(KuumaParentApp(slots: slots, gate: guardian.gate));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('pin-new')), '2580');
    await tester.enterText(find.byKey(const Key('pin-confirm')), '2580');
    await tester.pump();
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text('Alerts'), findsWidgets);
    expect(find.text('Children'), findsOneWidget);
    expect(find.text('Requests'), findsOneWidget);

    // A screen opened on top of the tabs is hidden too when the app goes to the background.
    await tester.tap(find.text('Children'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('add-child')));
    await tester.pumpAndSettle();
    expect(find.text('Apps to watch'), findsOneWidget);
    for (final st in [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]) {
      tester.binding.handleAppLifecycleStateChanged(st);
    }
    for (final st in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
      tester.binding.handleAppLifecycleStateChanged(st);
    }
    await tester.pumpAndSettle();
    expect(find.text('Apps to watch'), findsNothing);
    expect(find.byKey(const Key('pin-1')), findsOneWidget);
  });
}
