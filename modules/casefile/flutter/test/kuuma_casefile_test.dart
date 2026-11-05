import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_casefile/kuuma_casefile.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  testWidgets('registers its screens and they render', (tester) async {
    final slots = SlotRegistry();
    CasefileFlutterModule().register(slots, KuumaL10n.instance);
    final ids = [for (final s in KuumaSlot.all) ...slots.entries(s).map((e) => e.id)];
    expect(ids, ['casefile.cases']);
    for (final id in ids) {
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: Builder(builder: slots.byId(id)!.builder))));
      expect(find.textContaining('Casefile'), findsWidgets);
    }
  });
}
