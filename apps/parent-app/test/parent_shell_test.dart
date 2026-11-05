import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_parent_app/main.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  testWidgets('login placeholder leads to Guardian\'s registered screens', (tester) async {
    final slots = SlotRegistry();
    GuardianFlutterModule().register(slots, KuumaL10n.instance);
    await tester.pumpWidget(KuumaParentApp(slots: slots));
    await tester.tap(find.text('Continue as test parent'));
    await tester.pumpAndSettle();
    expect(find.text('Alerts'), findsWidgets);
    expect(find.text('Children'), findsOneWidget);
  });
}
