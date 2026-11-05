import 'package:flutter/material.dart';
import 'package:kuuma_casefile/kuuma_casefile.dart';
import 'package:kuuma_companion/kuuma_companion.dart';
import 'package:kuuma_guardian/kuuma_guardian.dart';
import 'package:kuuma_ui/kuuma_ui.dart';

import 'src/native_registry.dart';
import 'src/shell.dart';

/// Flutter halves of the modules. Their Kotlin halves are registered in KuumaRuntime.kt.
final List<KuumaFlutterModule> flutterModules = [
  CompanionFlutterModule(),
  CasefileFlutterModule(),
  GuardianFlutterModule(),
];

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final slots = SlotRegistry();
  for (final m in flutterModules) {
    m.register(slots, KuumaL10n.instance);
  }
  runApp(KuumaChildApp(slots: slots, registry: NativeRegistry()));
}

class KuumaChildApp extends StatelessWidget {
  const KuumaChildApp({super.key, required this.slots, required this.registry});

  final SlotRegistry slots;
  final NativeRegistry registry;

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Kuuma',
        theme: KuumaTheme.light(),
        darkTheme: KuumaTheme.dark(),
        supportedLocales: kuumaLocales,
        localizationsDelegates: kuumaLocalizationsDelegates,
        home: ChildShell(slots: slots, registry: registry),
      );
}
