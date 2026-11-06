import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_casefile/kuuma_casefile.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('ug.kuuma/module/casefile');
  final calls = <String>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      switch (call.method) {
        case 'listCases':
          return [
            {'caseId': 'c1', 'offenderLabel': 'Person A', 'incidentCount': 4, 'topSeverity': 'high'}
          ];
        case 'timeline':
          return [
            {'at': 1762336800000, 'kind': 'FLAG', 'appSource': 'whatsapp', 'category': 'insult', 'severity': 'high'},
            {
              'at': 1762336800000,
              'kind': 'MESSAGE',
              'sender': 'Person A',
              'fromChild': false,
              'text': 'invented insult'
            },
            {'at': 1762336900000, 'kind': 'MESSAGE', 'fromChild': true, 'text': 'leave me alone'},
          ];
        case 'verify':
          return {'intact': true, 'length': 9, 'root': 'abc', 'problems': 0};
        case 'requestShare':
          return 'r1';
        case 'shares':
          return [
            {'requestId': 'r1', 'recipientType': 'school', 'status': 'completed', 'link': 'http://k/verify/s/r1#k=x'}
          ];
        case 'export':
          return {'pdfPath': '/x.pdf', 'root': 'abc', 'downloadUri': 'content://media/downloads/1'};
        case 'sendLink':
        case 'openFile':
          return true;
      }
      return null;
    });
  });

  Widget app() {
    final slots = SlotRegistry();
    CasefileFlutterModule().register(slots, KuumaL10n.instance);
    return MaterialApp(home: Scaffold(body: Builder(builder: slots.byId('casefile.cases')!.builder)));
  }

  testWidgets('lists cases and opens the timeline with abusive text hidden', (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    expect(find.text('Person A'), findsOneWidget);
    expect(find.text('4 incidents'), findsOneWidget);
    await tester.tap(find.text('Person A'));
    await tester.pumpAndSettle();
    expect(find.text('invented insult'), findsNothing);
    expect(find.text('leave me alone'), findsOneWidget); // the child's own words are not hidden
    await tester.tap(find.text('Hidden message. Tap to show.'));
    await tester.pump();
    expect(find.text('invented insult'), findsOneWidget);
  });

  testWidgets('checks evidence and reports directly, with no parent step', (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Person A'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check evidence'));
    await tester.pumpAndSettle();
    expect(find.text('Evidence is intact: nothing has been changed.'), findsOneWidget);
    await tester.tap(find.text('Report'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('School'));
    await tester.pumpAndSettle();
    expect(find.text('Your report is ready. Send this link to them:'), findsOneWidget);
    expect(find.text('http://k/verify/s/r1#k=x'), findsOneWidget);
    // The phone's share menu opens straight away.
    expect(calls, containsAllInOrder(['listCases', 'timeline', 'verify', 'requestShare', 'shares', 'sendLink']));
  });

  testWidgets('Save as PDF puts it in Downloads and opens it', (tester) async {
    await tester.pumpWidget(app());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Person A'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save as PDF'));
    await tester.pumpAndSettle();
    expect(find.text('Saved to Downloads/Nilinde.'), findsOneWidget);
    expect(calls, containsAllInOrder(['export', 'openFile']));
    expect(find.text('Open the PDF'), findsOneWidget);
  });
}
