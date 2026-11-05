import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kuuma_child_app/main.dart';
import 'package:kuuma_child_app/src/native_registry.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('ug.kuuma/registry');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'modules') {
        return [
          {
            'namespace': 'sentinel',
            'displayName': 'Sentinel',
            'isFake': false,
            'screens': [],
            'permissions': [
              {'permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE'}
            ]
          },
          {
            'namespace': 'companion',
            'displayName': 'Companion',
            'isFake': false,
            'screens': [
              {'id': 'companion.home', 'slot': 'child.tab', 'titleKey': 'companion.tab.title'}
            ],
            'permissions': []
          },
          {
            'namespace': 'casefile',
            'displayName': 'Casefile',
            'isFake': false,
            'screens': [
              {'id': 'casefile.cases', 'slot': 'child.tab', 'titleKey': 'casefile.tab.title'}
            ],
            'permissions': []
          },
        ];
      }
      return const <dynamic>[];
    });
  });

  testWidgets('bottom navigation is built from the module registry', (tester) async {
    final slots = SlotRegistry();
    for (final m in flutterModules) {
      m.register(slots, KuumaL10n.instance);
    }
    await tester.pumpWidget(KuumaChildApp(slots: slots, registry: NativeRegistry()));
    await tester.pumpAndSettle();
    expect(find.text('Help'), findsWidgets); // Companion tab
    expect(find.text('My cases'), findsOneWidget); // Casefile tab
    expect(find.text('Modules'), findsOneWidget);
    await tester.tap(find.text('Modules'));
    await tester.pumpAndSettle();
    expect(find.text('Sentinel'), findsOneWidget);
  });
}
