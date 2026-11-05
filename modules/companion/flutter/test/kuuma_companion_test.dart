import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_companion/kuuma_companion.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  testWidgets('registers its screens and they render', (tester) async {
    final slots = SlotRegistry();
    CompanionFlutterModule().register(slots, KuumaL10n.instance);
    final ids = [for (final s in KuumaSlot.all) ...slots.entries(s).map((e) => e.id)];
    expect(ids, ['companion.home']);
    for (final id in ids) {
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: Builder(builder: slots.byId(id)!.builder))));
      expect(find.textContaining('Companion'), findsWidgets);
    }
  });
}
