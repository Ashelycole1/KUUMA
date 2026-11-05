import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_contracts/kuuma_contracts.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

Widget app(Widget child, {Locale locale = const Locale('en')}) => MaterialApp(
      locale: locale,
      supportedLocales: kuumaLocales,
      localizationsDelegates: kuumaLocalizationsDelegates,
      theme: KuumaTheme.light(),
      home: Scaffold(body: child),
    );

void main() {
  test('translations enforce the module prefix rule', () {
    expect(
        () => KuumaL10n.instance.register('companion', {
              'en': {'guardian.x.y': 'no'}
            }),
        throwsArgumentError);
    expect(() => KuumaL10n.instance.register('nobody', {'en': {}}), throwsArgumentError);
    KuumaL10n.instance.register('companion', {
      'en': {'companion.test.hello': 'Hello'},
      'sw': {'companion.test.hello': 'Habari'},
    });
    expect(KuumaL10n.instance.translate('companion.test.hello', 'sw'), 'Habari');
    expect(KuumaL10n.instance.translate('companion.test.hello', 'lg'), 'Hello'); // English fallback
  });

  test('slots enforce ownership', () {
    final slots = SlotRegistry();
    final entry = ScreenEntry(
        id: 'casefile.cases',
        slot: KuumaSlot.childTab,
        titleKey: 'casefile.tab.title',
        icon: Icons.folder,
        builder: (_) => const SizedBox());
    expect(() => slots.register('companion', entry), throwsArgumentError);
    slots.register('casefile', entry);
    expect(slots.entries(KuumaSlot.childTab).single.id, 'casefile.cases');
  });

  testWidgets('severity badge renders in Luganda (falls back for Material strings)', (tester) async {
    await tester.pumpWidget(app(const SeverityBadge(Severity.high), locale: const Locale('lg')));
    expect(find.text('High'), findsOneWidget);
  });

  testWidgets('lock screen unlocks with the right PIN only', (tester) async {
    var unlocked = false;
    await tester.pumpWidget(app(LockScreen(verifyPin: (p) async => p == '2468', onUnlocked: () => unlocked = true)));
    for (final d in ['1', '1', '1', '1']) {
      await tester.tap(find.byKey(Key('pin-$d')));
    }
    await tester.pumpAndSettle();
    expect(unlocked, isFalse);
    expect(find.text('That PIN is not right. Try again.'), findsOneWidget);
    for (final d in ['2', '4', '6', '8']) {
      await tester.tap(find.byKey(Key('pin-$d')));
    }
    await tester.pumpAndSettle();
    expect(unlocked, isTrue);
  });

  testWidgets('quick hide swaps in the decoy', (tester) async {
    final c = QuickHideController();
    await tester.pumpWidget(
        app(QuickHide(controller: c, lockScreen: (_) => const SizedBox(), child: const Text('secret screen'))));
    expect(find.text('secret screen'), findsOneWidget);
    c.hide();
    await tester.pump();
    expect(find.text('secret screen'), findsNothing);
    expect(find.text('Notes'), findsOneWidget);
  });

  test('neutral notifications reveal nothing', () {
    final n = NeutralNotification.build('en');
    expect(n.title, 'Reminder');
    expect('${n.title} ${n.body}'.toLowerCase(), isNot(contains('harass')));
  });
}
